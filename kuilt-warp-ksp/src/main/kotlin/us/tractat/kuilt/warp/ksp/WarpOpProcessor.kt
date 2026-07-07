package us.tractat.kuilt.warp.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility

/** Fully-qualified name of the marker annotation in `:kuilt-warp`. */
private const val WARP_OP_ANNOTATION = "us.tractat.kuilt.warp.WarpOp"

/** Fully-qualified name of the op contract in `:kuilt-warp`. */
private const val OP_INTERFACE = "us.tractat.kuilt.warp.Op"

/**
 * Entry point the KSP runtime service-loads (see
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`).
 *
 * Creates the processor that turns `@WarpOp`-annotated top-level ops into one
 * generated `WarpOps` registrar object per package.
 */
public class WarpOpProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        WarpOpProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Collects every `@WarpOp`-annotated top-level `val` of type `Op`, validates it,
 * and generates a `WarpOps : OpRegistrar` object per package (rendered by
 * [renderWarpOps]).
 *
 * Validation failures are reported through [KSPLogger.error] with the offending
 * symbol, so a bad declaration fails the build at its source location:
 * - the annotation target must be a **top-level, immutable `val`**;
 * - its type must be assignable to `us.tractat.kuilt.warp.Op`;
 * - it must be `public` or `internal` (a `private` val is invisible to the
 *   generated file);
 * - resolved op ids must be unique within the module (the compile-time twin of
 *   `OpRegistry.register`'s fail-loud duplicate check).
 */
internal class WarpOpProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // All @WarpOp symbols are plain declarations — none depend on generated
        // code — so a single pass over the first round sees everything.
        if (invoked) return emptyList()
        invoked = true

        val symbols = resolver.getSymbolsWithAnnotation(WARP_OP_ANNOTATION).toList()
        if (symbols.isEmpty()) return emptyList()

        val opType = resolver.getClassDeclarationByName(resolver.getKSNameFromString(OP_INTERFACE))
            ?.asStarProjectedType()
        if (opType == null) {
            logger.error("@WarpOp processing requires $OP_INTERFACE on the classpath — add a dependency on us.tractat.kuilt:kuilt-warp.")
            return emptyList()
        }

        val ops = symbols.mapNotNull { validate(it, opType) }
        reportDuplicateIds(ops)

        ops.groupBy { it.property.packageName.asString() }.forEach { (packageName, packageOps) ->
            generateRegistrar(packageName, packageOps.sortedBy { it.property.simpleName.asString() })
        }
        return emptyList()
    }

    private class ValidatedOp(val property: KSPropertyDeclaration, val opId: String)

    private fun validate(symbol: KSAnnotated, opType: KSType): ValidatedOp? {
        if (symbol !is KSPropertyDeclaration) {
            logger.error("@WarpOp is only applicable to top-level val properties of type $OP_INTERFACE.", symbol)
            return null
        }
        var valid = true
        if (symbol.parentDeclaration != null) {
            logger.error("@WarpOp property '${symbol.simpleName.asString()}' must be declared top-level, not inside a class or object.", symbol)
            valid = false
        }
        if (symbol.isMutable) {
            logger.error("@WarpOp property '${symbol.simpleName.asString()}' must be an immutable val.", symbol)
            valid = false
        }
        if (symbol.getVisibility() !in setOf(Visibility.PUBLIC, Visibility.INTERNAL)) {
            logger.error(
                "@WarpOp property '${symbol.simpleName.asString()}' must be public or internal — " +
                    "a private val is invisible to the generated WarpOps registrar in the same package.",
                symbol,
            )
            valid = false
        }
        if (!opType.isAssignableFrom(symbol.type.resolve())) {
            logger.error("@WarpOp property '${symbol.simpleName.asString()}' must be of type $OP_INTERFACE (declare it with shuttle { ... }).", symbol)
            valid = false
        }
        val explicitId = symbol.annotations
            .first { it.shortName.asString() == "WarpOp" && it.annotationType.resolve().declaration.qualifiedName?.asString() == WARP_OP_ANNOTATION }
            .arguments.firstOrNull { it.name?.asString() == "id" }?.value as? String ?: ""
        if (explicitId.isNotEmpty() && explicitId.isBlank()) {
            logger.error("@WarpOp id must not be blank — omit it to derive the id from the property name.", symbol)
            valid = false
        }
        if (!valid) return null
        return ValidatedOp(symbol, deriveOpId(explicitId, symbol.simpleName.asString()))
    }

    private fun reportDuplicateIds(ops: List<ValidatedOp>) {
        ops.groupBy { it.opId }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            duplicates.forEach { duplicate ->
                logger.error(
                    "Duplicate @WarpOp id '$id' — each op id must be registered exactly once " +
                        "(also declared at: ${duplicates.filterNot { it === duplicate }.joinToString { it.property.qualifiedName?.asString() ?: "?" }}).",
                    duplicate.property,
                )
            }
        }
    }

    private fun generateRegistrar(packageName: String, ops: List<ValidatedOp>) {
        val sourceFiles = ops.mapNotNull { it.property.containingFile }.distinct()
        // aggregating = true: which ops exist in this package is a whole-module
        // question — any new/removed @WarpOp source must regenerate the registrar.
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, "WarpOps").bufferedWriter().use { writer ->
            writer.write(
                renderWarpOps(
                    packageName = packageName,
                    ops = ops.map { GeneratedOp(propertyName = it.property.simpleName.asString(), opId = it.opId) },
                ),
            )
        }
    }
}

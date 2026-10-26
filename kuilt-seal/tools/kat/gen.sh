#!/bin/bash
# Builds one kuilt-seal v1 sealed box with tools independent of cryptography-kotlin — OpenSSL 3 for
# P-256 keys, ECDH and HKDF-SHA-256, and plain JCA (Gcm.java) for AES-256-GCM — and prints the
# hex that SealedBoxKnownAnswerTest embeds. Each run makes fresh keys, so the output differs every
# time; the test pins one run's output. Nothing is written outside a temporary directory.
#
#   OPENSSL=/path/to/openssl JAVA=/path/to/java (JDK 11+) kuilt-seal/tools/kat/gen.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
OSSL="${OPENSSL:-openssl}"
JAVA="${JAVA:-java}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
hex() { xxd -p "$1" | tr -d '\n'; }

"$OSSL" ecparam -name prime256v1 -genkey -noout -out r.pem
"$OSSL" ecparam -name prime256v1 -genkey -noout -out e.pem
"$OSSL" pkcs8 -topk8 -nocrypt -in r.pem -outform DER -out r.pk8
"$OSSL" ec -in r.pem -pubout -out r.pub.pem 2>/dev/null
"$OSSL" ec -in r.pem -pubout -conv_form uncompressed -outform DER -out r.spki 2>/dev/null
"$OSSL" ec -in e.pem -pubout -conv_form uncompressed -outform DER -out e.spki 2>/dev/null
tail -c 65 r.spki > r.raw
tail -c 65 e.spki > e.raw
"$OSSL" pkeyutl -derive -inkey e.pem -peerkey r.pub.pem -out shared.bin

LABEL_HEX=$(printf 'kuilt-seal/v1 P-256 HKDF-SHA-256 AES-256-GCM' | xxd -p | tr -d '\n')
INFO_HEX="${LABEL_HEX}$(hex e.raw)$(hex r.raw)"
SALT_HEX=$(printf '%064d' 0)
"$OSSL" kdf -keylen 32 -kdfopt digest:SHA256 -kdfopt hexkey:"$(hex shared.bin)" \
  -kdfopt hexsalt:"$SALT_HEX" -kdfopt hexinfo:"$INFO_HEX" -binary -out key.bin HKDF

NONCE_HEX=$("$OSSL" rand -hex 12)
AD_HEX=$(printf 'problem-report:v1' | xxd -p | tr -d '\n')
PT_HEX=$(printf 'the position, padded by the caller' | xxd -p | tr -d '\n')
CT_HEX=$("$JAVA" "$here/Gcm.java" "$(hex key.bin)" "$NONCE_HEX" "$AD_HEX" "$PT_HEX")

echo "recipientPkcs8=$(hex r.pk8)"
echo "recipientPublic=$(hex r.raw)"
echo "associatedData=$AD_HEX"
echo "plaintext=$PT_HEX"
echo "sealed=01$(hex e.raw)${NONCE_HEX}${CT_HEX}"

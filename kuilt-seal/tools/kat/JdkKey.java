import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.HexFormat;

// A P-256 key pair as SunEC writes it: PKCS #8 for the private half, and the raw 65-byte point.
public class JdkKey {
    public static void main(String[] a) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] x = fixed(pub.getW().getAffineX().toByteArray());
        byte[] y = fixed(pub.getW().getAffineY().toByteArray());
        HexFormat h = HexFormat.of();
        System.out.println("provider=" + kp.getPrivate().getClass().getName());
        System.out.println("pkcs8=" + h.formatHex(kp.getPrivate().getEncoded()));
        System.out.println("public=04" + h.formatHex(x) + h.formatHex(y));
    }

    private static byte[] fixed(byte[] v) {
        byte[] out = new byte[32];
        int n = Math.min(v.length, 32);
        System.arraycopy(v, v.length - n, out, 32 - n, n);
        return out;
    }
}

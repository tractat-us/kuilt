import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

// AES-256-GCM through plain JCA, independent of cryptography-kotlin.
// args: keyHex nonceHex adHex plaintextHex -> prints ciphertext||tag hex
public class Gcm {
    public static void main(String[] a) throws Exception {
        HexFormat h = HexFormat.of();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(h.parseHex(a[0]), "AES"), new GCMParameterSpec(128, h.parseHex(a[1])));
        c.updateAAD(h.parseHex(a[2]));
        System.out.println(h.formatHex(c.doFinal(h.parseHex(a[3]))));
    }
}

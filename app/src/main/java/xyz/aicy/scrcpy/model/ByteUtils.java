package xyz.aicy.scrcpy.model;

/**
 * Created by Alexandr Golovach on 27.06.16.
 * https://www.github.com/alexmprog/VideoCodec
 */
public class ByteUtils {

    public static byte[] longToBytes(long x) {
        return new byte[]{
                (byte) (x >>> 56),
                (byte) (x >>> 48),
                (byte) (x >>> 40),
                (byte) (x >>> 32),
                (byte) (x >>> 24),
                (byte) (x >>> 16),
                (byte) (x >>> 8),
                (byte) x
        };
    }

    public static long bytesToLong(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("bytesToLong requires at least 8 bytes");
        }
        // The first byte is intentionally NOT masked: it is sign-extended to long so that a
        // high-bit-set PTS reproduces the same negative value the previous BigInteger-based
        // implementation returned (BigInteger(byte[]) is signed two's-complement big-endian).
        return ((long) bytes[0] << 56)
                | ((bytes[1] & 0xFFL) << 48)
                | ((bytes[2] & 0xFFL) << 40)
                | ((bytes[3] & 0xFFL) << 32)
                | ((bytes[4] & 0xFFL) << 24)
                | ((bytes[5] & 0xFFL) << 16)
                | ((bytes[6] & 0xFFL) << 8)
                | (bytes[7] & 0xFFL);
    }

    public static byte[] intToBytes(int x) {
        return new byte[]{
                (byte) (x >>> 24),
                (byte) (x >>> 16),
                (byte) (x >>> 8),
                (byte) x
        };
    }

    public static int bytesToInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("bytesToInt requires at least 4 bytes");
        }
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }
}

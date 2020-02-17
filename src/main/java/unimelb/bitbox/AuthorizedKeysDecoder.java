/**
 * Author: WhiteFang34
 * From: GitHub
 * Available: https://stackoverflow.com/questions/3531506/using-public-key-from-authorized-keys-with-java-security
 * Encode PublicKey (DSA or RSA encoded) to authorized_keys like string
 *
 * @param publicKey DSA or RSA encoded
 * @param user username for output authorized_keys like string
 * @return authorized_keys like string
 * @throws IOException
 */

package unimelb.bitbox;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

public class AuthorizedKeysDecoder {
	private byte[] bytes;
	private int pos;

	public PublicKey decodePublicKey(String keyLine) throws Exception {
		bytes = null;
		pos = 0;

		// look for the Base64 encoded part of the line to decode
		// both ssh-rsa and ssh-dss begin with "AAAA" due to the length bytes
		for (String part : keyLine.split(" ")) {
			if (part.startsWith("AAAA")) {
				bytes = Base64.getDecoder().decode(part);
				break;
			}
		}
		if (bytes == null) {
			throw new IllegalArgumentException("no Base64 part to decode");
		}

		String type = decodeType();
		if (type.equals("ssh-rsa")) {
			BigInteger e = decodeBigInt();
			BigInteger m = decodeBigInt();
			RSAPublicKeySpec spec = new RSAPublicKeySpec(m, e);
			return KeyFactory.getInstance("RSA").generatePublic(spec);
		} else if (type.equals("ssh-dss")) {
			BigInteger p = decodeBigInt();
			BigInteger q = decodeBigInt();
			BigInteger g = decodeBigInt();
			BigInteger y = decodeBigInt();
			DSAPublicKeySpec spec = new DSAPublicKeySpec(y, p, q, g);
			return KeyFactory.getInstance("DSA").generatePublic(spec);
		} else {
			throw new IllegalArgumentException("unknown type " + type);
		}
	}

	private String decodeType() {
		int len = decodeInt();
		String type = new String(bytes, pos, len);
		pos += len;
		return type;
	}

	private int decodeInt() {
		return ((bytes[pos++] & 0xFF) << 24) | ((bytes[pos++] & 0xFF) << 16) | ((bytes[pos++] & 0xFF) << 8) | (bytes[pos++] & 0xFF);
	}

	private BigInteger decodeBigInt() {
		int len = decodeInt();
		byte[] bigIntBytes = new byte[len];
		System.arraycopy(bytes, pos, bigIntBytes, 0, len);
		pos += len;
		return new BigInteger(bigIntBytes);
	}
}
package com.ripple.xrpl4j.codec.binary;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.primitives.UnsignedLong;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ByteUtils {

  public static byte[] toByteArray(int value, int byteSize) {
    BigInteger bigInteger = checkSize(byteSize * Byte.SIZE, BigInteger.valueOf(value));
    return copyToEnd(byteSize, bigInteger);
  }

  public static byte[] toByteArray(BigInteger value, int byteSize) {
    BigInteger bigInteger = checkSize(byteSize * Byte.SIZE, value);
    return copyToEnd(byteSize, bigInteger);
  }

  private static byte[] copyToEnd(int byteSize, BigInteger bigInteger) {
    byte[] target = new byte[byteSize];
    byte[] source = bigInteger.toByteArray();
    for (int i = 0; i < source.length; i++) {
      target[byteSize - i - 1] = source[source.length - i - 1];
    }
    return target;
  }

  public static List<UnsignedByte> parse(String hex) {
    String padded = padded(hex);
    List<UnsignedByte> result = new ArrayList<>();
    for(int i = 0; i < padded.length(); i+=2) {
      result.add(UnsignedByte.of(padded.substring(i, i + 2)));
    }
    return result;
  }

  public static BigInteger checkSize(int expectedBits, BigInteger value) {
    Preconditions.checkArgument(value.bitLength() <= expectedBits);
    return value;
  }

  public static String toHex(List<UnsignedByte> segments) {
    return Joiner.on("").join(segments.stream().map(UnsignedByte::hexValue).collect(Collectors.toList()));
  }

  public static UnsignedLong toUnsignedLong(List<UnsignedByte> segments) {
    return UnsignedLong.valueOf(toHex(segments), 16);
  }

  public static String padded(String hex) {
    return hex.length() % 2 == 0 ? hex : "0" + hex;
  }

  public static String padded(String hex, int hexLength) {
    return Strings.repeat("0", hexLength - hex.length()) + hex;
  }

}

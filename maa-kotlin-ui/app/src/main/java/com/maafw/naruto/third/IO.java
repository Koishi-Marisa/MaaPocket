package com.maafw.naruto.third;

import java.io.IOException;
import java.io.InputStream;

/**
 * IO 工具。
 */
public final class IO {

    public static void copy(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = input.read(buffer)) != -1) {
            output.write(buffer, 0, len);
        }
    }

    public static String toString(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = input.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len, "UTF-8"));
        }
        return sb.toString();
    }
}
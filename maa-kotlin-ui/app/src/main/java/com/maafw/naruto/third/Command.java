package com.maafw.naruto.third;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shell 命令执行工具。
 */
public final class Command {

    public static String execReadOutput(String... command) throws Exception {
        Process process = Runtime.getRuntime().exec(command);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        process.waitFor();
        return sb.toString();
    }

    public static int exec(String... command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            return process.waitFor();
        } catch (Exception e) {
            Ln.e("Command exec failed: " + e.getMessage());
            return -1;
        }
    }
}
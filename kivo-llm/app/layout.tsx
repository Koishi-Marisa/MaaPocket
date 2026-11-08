import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Kivo LLM Gateway",
  description: "国服《蔚蓝档案》角色资料，面向 LLM 的搜索与 Markdown 接口。",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}

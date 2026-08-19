import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emits .next/standalone: the server plus only the node_modules it actually
  // reaches, so the runtime image does not carry a 400 MB dependency tree it
  // never opens. Required by frontend/Dockerfile; harmless for `next dev`.
  output: "standalone",
};

export default nextConfig;

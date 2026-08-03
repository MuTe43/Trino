import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// Coarse "are you logged in at all" gate: the access token lives only in
// in-memory JS, unreachable from the edge, so we gate on refreshToken cookie
// presence. Real role enforcement happens server-side via @PreAuthorize.
export function middleware(request: NextRequest) {
  const refreshToken = request.cookies.get("refreshToken");
  if (!refreshToken) {
    const url = request.nextUrl.clone();
    url.pathname = "/connexion";
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/exploitation/:path*", "/admin/:path*"],
};

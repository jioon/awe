package ai.awe.registry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Component public final class ApiAuthFilter extends OncePerRequestFilter {
  private final String author,reviewer;
  public ApiAuthFilter(@Value("${awe.auth.author-token:}") String author,
                       @Value("${awe.auth.reviewer-token:}") String reviewer) {
    this.author=author; this.reviewer=reviewer;
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
      throws ServletException,IOException {
    res.setHeader("Cache-Control","no-store");
    if(author.isBlank() || reviewer.isBlank() || secureEquals(author,reviewer)) {
      res.sendError(503,"Distinct bootstrap author and reviewer credentials required"); return;
    }
    String auth=req.getHeader("Authorization");
    if(auth==null || !auth.startsWith("Bearer ")) {res.sendError(401,"Bearer token required");return;}
    String provided=auth.substring(7);
    String role=secureEquals(provided,author)?"author":secureEquals(provided,reviewer)?"reviewer":null;
    if(role==null) {res.sendError(401,"Invalid bearer token");return;}
    boolean write=!switch(req.getMethod()) {case "GET","HEAD","OPTIONS" -> true; default -> false;};
    if(write) {
      boolean approve=req.getRequestURI().matches("/api/drafts/[^/]+/(approve|publish)");
      if(approve && !role.equals("reviewer")) {res.sendError(403,"Reviewer required");return;}
      if(!approve && !role.equals("author")) {res.sendError(403,"Author required");return;}
    }
    req.setAttribute("awe.actor","bootstrap-"+role);
    chain.doFilter(req,res);
  }
  private static boolean secureEquals(String a,String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
  }
}
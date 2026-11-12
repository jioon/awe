package ai.awe.registry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.NoSuchElementException;
@RestControllerAdvice
public final class RegistryErrors {
  private final JsonDocuments json;
  public RegistryErrors(JsonDocuments json){this.json=json;}
  @ExceptionHandler({IllegalArgumentException.class,DataIntegrityViolationException.class})
  public ResponseEntity<String> badRequest(Exception e) {
    String message=e instanceof DataIntegrityViolationException?"Conflict or integrity violation":e.getMessage();
    return reply(HttpStatus.BAD_REQUEST,"BAD_REQUEST",message);
  }
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<String> notFound(Exception e) {
    return reply(HttpStatus.NOT_FOUND,"NOT_FOUND",e.getMessage());
  }
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<String> state(Exception e) {
    return reply(HttpStatus.CONFLICT,"CONFLICT",e.getMessage());
  }
  private ResponseEntity<String> reply(HttpStatus status,String code,String message) {
    return ResponseEntity.status(status).header("Cache-Control","no-store")
      .header("Content-Type","application/json; charset=utf-8")
      .body(json.write(Map.of("error",code,"message",message)));
  }
}
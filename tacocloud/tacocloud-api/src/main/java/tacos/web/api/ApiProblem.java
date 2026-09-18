package tacos.web.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiProblem {

  private final String type;
  private final String title;
  private final int status;
  private final String detail;
  private final String instance;
  private final String code;
  private final List<Violation> violations;

  public ApiProblem(String type, String title, int status, String detail,
                    String instance, String code, List<Violation> violations) {
    this.type = type;
    this.title = title;
    this.status = status;
    this.detail = detail;
    this.instance = instance;
    this.code = code;
    this.violations = violations;
  }

  public static ApiProblem of(String type, String title, int status, String detail,
                              String instance, String code) {
    return new ApiProblem(type, title, status, detail, instance, code, List.of());
  }

  public static ApiProblem of(String type, String title, int status, String detail,
                              String instance, String code, List<Violation> violations) {
    return new ApiProblem(type, title, status, detail, instance, code, violations);
  }

  @Data
  public static class Violation {
    private final String field;
    private final String message;
  }
}
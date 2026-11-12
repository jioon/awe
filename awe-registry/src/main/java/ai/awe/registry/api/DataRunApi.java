package ai.awe.registry.api;

import ai.awe.registry.runtime.DataRunService;
import ai.awe.registry.support.JsonDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 外部 Agent/Skill 只调用版本化的数据能力，不向 AWE 注入自定义脚本。 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DataRunApi {
    private final DataRunService runs;
    private final JsonDocuments json;

    public DataRunApi(DataRunService runs, JsonDocuments json) {
        this.runs = runs;
        this.json = json;
    }

    @PostMapping("/data-runs")
    public String create(@RequestBody String body, HttpServletRequest request) {
        return json.write(
                runs.create(json.object(body), (String) request.getAttribute("awe.actor")));
    }

    @GetMapping("/data-runs")
    public String list() {
        return json.write(runs.list());
    }

    @GetMapping("/data-runs/{id}")
    public String get(@PathVariable UUID id) {
        return json.write(runs.get(id));
    }

    @PostMapping("/data-runs/{id}/replays")
    public String replay(
            @PathVariable UUID id, @RequestBody String body, HttpServletRequest request) {
        return json.write(
                runs.replay(id, json.object(body), (String) request.getAttribute("awe.actor")));
    }

    @GetMapping("/data-gaps")
    public String gaps() {
        return json.write(runs.gaps());
    }
}

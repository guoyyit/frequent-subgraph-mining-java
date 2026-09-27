package com.github.guoyyit.frequentsubgraphminingjava.web;

import com.github.guoyyit.frequentsubgraphminingjava.mining.MiningOutcome;
import com.github.guoyyit.frequentsubgraphminingjava.service.GraphChangeRequest;
import com.github.guoyyit.frequentsubgraphminingjava.service.MiningQuery;
import com.github.guoyyit.frequentsubgraphminingjava.service.MiningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图变更与挖掘查询的 REST 接口。
 *
 * <ul>
 *   <li>POST /api/mining/graphs 提交一次图集合变更（ADD/UPDATE/DELETE）；</li>
 *   <li>POST /api/mining/query  在当前一致快照上执行挖掘查询；</li>
 *   <li>GET  /api/mining/version 查询当前已提交目录版本。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mining")
public class MiningController {

    private final MiningService miningService;

    public MiningController(MiningService miningService) {
        this.miningService = miningService;
    }

    @PostMapping("/graphs")
    public ChangeResponse change(@RequestBody GraphChangeRequest request) {
        long version = miningService.commitChange(request);
        return new ChangeResponse(version);
    }

    @PostMapping("/query")
    public MiningOutcome query(@RequestBody(required = false) MiningQuery query) {
        return miningService.query(query);
    }

    @GetMapping("/version")
    public VersionResponse version() {
        return new VersionResponse(miningService.currentVersion());
    }

    /** 变更响应。 */
    public static final class ChangeResponse {
        private long version;

        public ChangeResponse() {
        }

        public ChangeResponse(long version) {
            this.version = version;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }
    }

    /** 版本响应。 */
    public static final class VersionResponse {
        private long version;

        public VersionResponse() {
        }

        public VersionResponse(long version) {
            this.version = version;
        }

        public long getVersion() {
            return version;
        }

        public void setVersion(long version) {
            this.version = version;
        }
    }
}

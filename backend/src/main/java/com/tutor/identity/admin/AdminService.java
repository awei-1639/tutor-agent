package com.tutor.identity.admin;

import com.tutor.identity.auth.AuthContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Administrator application use cases; persistence is isolated in AdminStore. */
@Service
public class AdminService {
    private final AdminStore store;

    @Autowired
    public AdminService(AdminStore store) { this.store = store; }


    public Map<String,Object> overview() {
        long adminId=requireAdmin(); List<Map<String,Object>> evalRuns=store.recentEvalRuns(); Map<String,Object> metrics=store.interviewMetrics();
        long total=((Number)metrics.get("total_feedback")).longValue(), inaccurate=((Number)metrics.get("inaccurate_feedback")).longValue();
        Map<String,Object> quality=new LinkedHashMap<>(); quality.put("finalizedSessions",((Number)metrics.get("finalized_sessions")).longValue()); quality.put("totalFeedback",total); quality.put("inaccurateFeedback",inaccurate); quality.put("inaccurateRate",total==0?0D:(double)inaccurate/total); quality.put("avgConfidence",((Number)metrics.get("avg_confidence")).doubleValue()); quality.put("recentCalibration",store.recentCalibration());
        return new LinkedHashMap<>(Map.of("operatorId",adminId,"users",store.overviewUsers(),"recentEvalRuns",evalRuns,"interviewQuality",quality,"checks",Map.of("database","available","evaluation",evalRuns.stream().anyMatch(r->"running".equals(r.get("status")))?"running":"idle")));
    }

    public Map<String,Object> listUsers(String search,String status,int page,int size) { requireAdmin(); int p=Math.max(0,page), s=Math.min(Math.max(size,1),100); return Map.of("items",store.users(search,status,p,s),"page",p,"size",s,"total",store.userCount(search,status)); }
    public void disable(long id){long a=requireAdmin();assertNotSelf(a,id);if(store.disable(id)==0)throw notFound();store.audit(a,"USER_DISABLED",id,null);}
    public void restore(long id){long a=requireAdmin();if(store.restore(id)==0)throw notFound();store.audit(a,"USER_RESTORED",id,null);}
    public void softDelete(long id){long a=requireAdmin();assertNotSelf(a,id);if(store.softDelete(id)==0)throw notFound();store.audit(a,"USER_SOFT_DELETED",id,null);}
    public List<Map<String,Object>> audit(int limit){requireAdmin();return store.audit(Math.min(Math.max(limit,1),100));}
    public long requireAdmin(){Long id=AuthContext.currentUserId();if(id==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"未登录");if(!store.isAdmin(id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");return id;}
    public void audit(long adminId,String action,long targetId){store.audit(adminId,action,targetId,null);}
    public void auditEvent(long adminId,String action,String metadataJson){store.audit(adminId,action,null,metadataJson);}
    private void assertNotSelf(long a,long t){if(a==t)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不能修改当前管理员账号");}
    private static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在或状态不允许此操作");}
}

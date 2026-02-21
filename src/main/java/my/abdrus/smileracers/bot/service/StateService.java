package my.abdrus.smileracers.bot.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class StateService {

    public enum State {
        NONE,
        WAITING_FOR_AMOUNT
    }

    public static class Session {
        State state;
        Long targetUserId;

        Session(State state, Long targetUserId) {
            this.state = state;
            this.targetUserId = targetUserId;
        }
    }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    public void setWaitingAmount(Long targetUserId) {
        sessions.put(targetUserId, new Session(State.WAITING_FOR_AMOUNT, targetUserId));
    }

    public Session getSession(Long targetUserId) {
        return sessions.getOrDefault(targetUserId, new Session(State.NONE, null));
    }

    public void clear(Long targetUserId) {
        sessions.remove(targetUserId);
    }
}

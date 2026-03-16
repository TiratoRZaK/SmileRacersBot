package my.abdrus.emojirace.bot.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Data
@Table(name = "USER_NOTIFICATIONS")
public class UserNotification {

    @Id
    @SequenceGenerator(name = "user_notifications_seq", sequenceName = "user_notifications_seq", allocationSize = 50)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_notifications_seq")
    private Long id;

    @Column(name = "USER_CHAT_ID", nullable = false)
    private Long userChatId;

    @Column(name = "TEXT", nullable = false, length = 2000)
    private String text;

    @Column(name = "MESSAGE_ID")
    private Integer messageId;

    @Column(name = "CREATED_DATE", nullable = false)
    private Date createdDate = new Date();
}

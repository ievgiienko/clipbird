package com.secureclipboard.repository;

import com.secureclipboard.domain.TelegramSubscriber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TelegramSubscriberRepository extends JpaRepository<TelegramSubscriber, Long> {

    boolean existsByChatId(Long chatId);

    @Query("SELECT s.chatId FROM TelegramSubscriber s")
    List<Long> findAllChatIds();
}

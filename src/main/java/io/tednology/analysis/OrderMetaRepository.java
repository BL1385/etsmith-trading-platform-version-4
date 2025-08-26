package io.tednology.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author Edward Smith
 */
public interface OrderMetaRepository extends JpaRepository<OrderMeta, Long> {

    List<OrderMeta> findByCreateTimeBetweenAndSltpEqualsOrderByCreateTime(LocalDateTime start, LocalDateTime end, String sltp);

    Stream<OrderMeta> findByCreateTimeAfterAndCloseTimeBeforeAndSltpEquals(LocalDateTime start, LocalDateTime end, String sltp);

    @Transactional
    void deleteByCreateTimeBetweenAndSltpEquals(LocalDateTime start, LocalDateTime end, String sltp);

}

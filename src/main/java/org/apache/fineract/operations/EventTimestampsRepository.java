package org.apache.fineract.operations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EventTimestampsRepository extends JpaRepository<EventTimestamps, String>, JpaSpecificationExecutor {

    @Modifying
    @Transactional
    @Query("DELETE FROM EventTimestamps")
    void deleteAllTimestamps();

}

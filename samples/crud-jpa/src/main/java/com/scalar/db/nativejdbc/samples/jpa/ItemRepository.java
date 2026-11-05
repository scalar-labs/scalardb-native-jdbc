package com.scalar.db.nativejdbc.samples.jpa;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Items, by Hibernate's generated statements. */
public interface ItemRepository extends JpaRepository<Item, Long> {

  /** Items of a name, a derived query. */
  List<Item> findByName(String name);

  /** An item read under a write lock, FOR UPDATE or the engine's equivalent. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Item> findLockedById(long id);
}

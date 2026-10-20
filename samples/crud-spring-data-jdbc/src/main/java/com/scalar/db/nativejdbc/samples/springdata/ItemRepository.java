package com.scalar.db.nativejdbc.samples.springdata;

import java.util.List;
import java.util.Optional;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.CrudRepository;

/** Items, by Spring Data JDBC's generated statements. */
public interface ItemRepository extends CrudRepository<Item, Long> {

  /** Items of a name, a derived query. */
  List<Item> findByName(String name);

  /** An item read under a write lock, FOR UPDATE or the engine's equivalent. */
  @Lock(LockMode.PESSIMISTIC_WRITE)
  Optional<Item> findLockedById(long id);
}

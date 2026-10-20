package com.scalar.db.nativejdbc.samples.springdata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** A transfer between items in one transaction, rolled back when the source has too little. */
@Service
public class ItemService {

  private final ItemRepository items;

  public ItemService(ItemRepository items) {
    this.items = items;
  }

  /**
   * Moves {@code qty} from one item to another.
   *
   * @throws IllegalStateException when the source has too little, after which nothing changed
   */
  @Transactional
  public void restock(long id, int qty) {
    // locked first, so that no other writer, ScalarDB's included, changes it in between
    Item item = items.findLockedById(id).orElseThrow();
    item.setQty(item.getQty() + qty);
    items.save(item);
  }

  /**
   * The total quantity, read at SERIALIZABLE: no transaction's writes are half seen. Not {@code
   * readOnly}, which MySQL Connector/J does not allow at SERIALIZABLE through the driver (todo
   * 301).
   */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public int total() {
    int total = 0;
    for (Item item : items.findAll()) {
      total += item.getQty();
    }
    return total;
  }

  /** As {@link #total}, in a read-only transaction: not on MySQL (todo 301). */
  @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
  public int totalReadOnly() {
    return total();
  }

  @Transactional
  public void transfer(long from, long to, int qty) {
    Item source = items.findById(from).orElseThrow();
    Item target = items.findById(to).orElseThrow();
    target.setQty(target.getQty() + qty);
    items.save(target);
    // written before the check, so that the rollback has something to undo
    source.setQty(source.getQty() - qty);
    items.save(source);
    if (source.getQty() < 0) {
      throw new IllegalStateException("item " + from + " has too little");
    }
  }
}

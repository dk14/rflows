package api.routing.metrics

import java.util.Comparator
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/**
  * Created by dkondratiuk on 6/7/15.
  */
final class ConcurrentRingSet[K](val maxSize: Int)(val onRemove: K => Unit) extends Iterable[K] {

  @volatile private var isActive = true

  private lazy val set = new ConcurrentSkipListSet[PositionedKey](new Comparator[PositionedKey] {
    override def compare(o1: PositionedKey, o2: PositionedKey): Int = if (o1.key == o2.key) 0 else if (o1.position > o2.position) 1 else -1
  })

  private case class PositionedKey(position: Long, key: K) {
    override def hashCode = key.hashCode()
    override def equals(a: Any) = a match { case e: PositionedKey => key.equals(e.key); case x => super.equals(x) }
  }

  private val count = new AtomicLong(0)

  def put(k: K): Boolean = if (isActive) {
    if (count.get() == Long.MaxValue + 1) { set.clear(); count.set(0) } //Just in case...
    val index = count.incrementAndGet()
    while (set.size >= maxSize) onRemove(set.pollFirst().key)
    set.add(PositionedKey(index, k))
  }
  else false

  def close() = {
    isActive = false
    this
  }

  import scala.collection.JavaConverters._
  override def iterator: Iterator[K] = set.descendingIterator().asScala.map(_.key)
}


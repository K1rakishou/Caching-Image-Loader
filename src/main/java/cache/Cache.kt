package cache

interface Cache<K, V> {
  fun store(key: K, value: V)
  fun get(key: K): V?
  fun remove(key: K)
  fun clear()
}
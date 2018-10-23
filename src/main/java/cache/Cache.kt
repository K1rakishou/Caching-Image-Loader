package cache

interface Cache<K, V> {
  fun store(key: K, value: V)
  fun get(key: K): V?
  fun contains(key: K): Boolean
  fun remove(key: K)
}
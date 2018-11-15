package cache

interface Cache<K, V> {
  suspend fun store(key: K, value: V)
  suspend fun contains(key: K): Boolean
  suspend fun get(key: K): V?
  suspend fun remove(key: K)
  suspend fun size(): Int
  suspend fun evictOldest(): V?
  suspend fun clear()
}
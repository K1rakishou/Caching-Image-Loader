package cache

interface Cache<K, V> {
  suspend fun store(key: K, value: V)
  suspend fun get(key: K): V?
  suspend fun remove(key: K)
  suspend fun clear()
}
package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.ProviderInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 内置平台实现的注册表。
 *
 * 多数平台实现已经搬进 :core，可以直接构造；少数必须留在 :app 的实现（Apple 要 Widevine
 * 的 DRM 回调与 Context，Spotify 要 android.util.Base64，MHub 要 media3 的数据源壳）由适配层
 * 通过 [registerFactory] 先登记，再在这里按原来的顺序排队。
 *
 * **登记时机**：`Application.onCreate` 里第一件事就登记；单元测试没有 Application 生命周期，
 * 由用例的 `@BeforeClass` 登记（见 `NativeProvidersTest`）。列表在第一次 [all] 时构造，
 * 但**登记会作废已构造的列表**——这样"某个用例先摸了一次 NativeProviders"也不会把后登记的
 * 平台永久挡在外面。App 运行期登记只发生一次，列表因此只构造一次。
 *
 * 某个工厂位始终没人登记时（例如桌面端不做 Apple）：[addProvider] 按老样子打印
 * "provider init failed" 并跳过该平台，其余平台照常可用——与搬迁前某个平台构造抛异常时一致。
 */
object NativeProviders {
    /** 静态平台的数量上限：13 个自带 + apple / spotify / mhub 三个由平台层登记。 */
    private const val EXPECTED_STATIC = 16

    /** 留在 :app 的平台实现：id -> 构造工厂，由适配层登记。 */
    private val externalFactories = ConcurrentHashMap<String, () -> NativeProvider>()

    /** 已构造的静态列表；[registerFactory] 会把它作废，等下次 [all] 重建。 */
    @Volatile
    private var cachedStatic: List<NativeProvider>? = null

    /**
     * 登记一个由平台层承载的内置实现。重复登记以最后一次为准（热重载、测试里换替身都靠它）。
     *
     * 会把已构造的静态列表作废：列表里可能有"该平台被跳过"的结果，登记后必须重建才能补上。
     */
    fun registerFactory(id: String, factory: () -> NativeProvider) {
        synchronized(this) {
            externalFactories[id] = factory
            cachedStatic = null
        }
    }

    private fun staticProviders(): List<NativeProvider> = synchronized(this) {
        cachedStatic?.let { return it }
        val built = buildList {
            addProvider { NetEaseProvider() }
            addProvider { QQProvider() }
            addProvider { KugouProvider() }
            addProvider { KuwoProvider() }
            addProvider { MiguProvider() }
            addProvider { external("apple") }
            addProvider { JooxProvider() }
            addProvider { QianqianProvider() }
            addProvider { BilibiliProvider() }
            addProvider { FivesingProvider() }
            addProvider { JamendoProvider() }
            addProvider { external("spotify") }
            addProvider { YouTubeMusicProvider() }
            addProvider { external("mhub") }
            addProvider { SodaProvider() }
            addProvider { YuntingProvider() }
        }
        // 缺平台的列表也缓存：桌面端永远缺 apple/spotify/mhub，不缓存的话每次取 provider
        // 都会重建 13 个实例并打印三份 "provider init failed" 堆栈；registerFactory 会作废
        // 缓存，所以晚登记的平台仍能补上（见 registerFactory 的 cachedStatic = null）
        cachedStatic = built
        built
    }

    private fun external(id: String): NativeProvider {
        val factory = externalFactories[id]
            ?: throw IllegalStateException("$id provider 由平台层实现，需先调用 NativeProviders.registerFactory(\"$id\")")
        return factory()
    }

    private fun MutableList<NativeProvider>.addProvider(factory: () -> NativeProvider) {
        try {
            add(factory())
        } catch (error: Throwable) {
            System.err.println("NativeProviders: provider init failed")
            error.printStackTrace()
        }
    }

    private val dynamicProviders = CopyOnWriteArrayList<NativeProvider>()

    @Synchronized
    fun register(provider: NativeProvider) {
        val static = staticProviders()
        require(static.none { it.info.id == provider.info.id }) { "duplicate native provider: ${provider.info.id}" }
        dynamicProviders.removeAll { it.info.id == provider.info.id }
        dynamicProviders += provider
    }

    fun unregister(id: String) {
        dynamicProviders.removeAll { it.info.id == id }
    }

    fun get(id: String): NativeProvider? = all().firstOrNull { it.info.id == id }
    fun all(): List<NativeProvider> = staticProviders() + dynamicProviders
    fun infos(): List<ProviderInfo> = all().map { it.info }

    fun newSessionId(providerId: String): String = "$providerId:${UUID.randomUUID()}"

    fun providerForSession(sessionId: String): NativeProvider? {
        val providerId = sessionId.substringBefore(':', "")
        if (providerId.isBlank() || !sessionId.contains(':')) return null
        return get(providerId)
    }
}

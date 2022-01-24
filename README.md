## KNet
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

Android network client based on Cronet. This library let you easily use HTTP/3 and QUIC protocol in your Android projects.
You can see more about QUIC here - [mobile](https://www.highload.ru/spb/2021/abstracts/8037) or [product](https://www.highload.ru/spb/2021/abstracts/8035) or [backend](https://www.highload.ru/spb/2021/abstracts/8034)

### Order:
- [Wrapper improovements](#impr)
- [Gradle](#gradle)
- [Initialization](#init)
- [OkHttp Integration](#okhttp)
- [Comming Soon](#soon)
- [Licence](#lic)

## <a name="impr"></a> Cronet wrapper improvements:
- Memory Optimizations
- Multithreading Optimizations
- Redirects Settings
- Quic Configurations
- Timeouts Settings
- Broken Hosts Clearing
- Documentation
- Interceptor System
- Debug System
- Metrics
- Error Handling
- **(soon)** Fallback System
- **(soon)** Encodings
- **(soon)** Tests
- **(soon)** Integrations(ExoPlayer/Ktor/Okhttp/Flipper/Stetho)
- **(soon)** Backoff
- **(soon)** DNS Prefetch

## <a name="gradle"></a> Gradle
You can try this library using Gradle:
``` groovy
dependencies {
 implementation 'com.vk.knet:core:1.0'
 implementation 'com.vk.knet:cronet:1.0'
 implementation 'com.vk.knet:okcronet:1.0'
}
```

## <a name="init"></a> Initialization
Short version:
```kotlin
val cronet = CronetKnetEngine.Build(App.context) {
    client {
        enableQuic(
            CronetQuic(hints = listOf(Host("drive.google.com", 443)))
        )
    }
}

val knetCronet = Knet.Build(cronet)
```

Full version:
```kotlin
CronetLogger.global(
    object : CronetHttpLogger {
        override fun error(vararg obj: Any) {
            Log.e("Logos", obj.toList().toString())
        }

        override fun debug(type: CronetHttpLogger.DebugType, vararg obj: Any) {
            Log.d("[${type.name}]", obj.toList().toString())
        }

        override fun info(vararg obj: Any) {
            Log.i("Logos", obj.toList().toString())
        }
    }
)

private val cronet = CronetKnetEngine.Build(App.context) {
    client {
        setCache(CronetCache.Disk(App.context.filesDir, 1024 * 1024 * 10))

        enableHttp2(true)
        enableQuic(
            CronetQuic(
                hints = listOf(
                    Host("drive.google.com", 443)
                ),
            )
        )

        useBrotli(true)
        isClearBrokenHosts(true)

        netlog(CronetLog.Config(netDir, 1024 * 1024 * 100))

        connectTimeout(15, TimeUnit.SECONDS)
        writeTimeout(15, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)

        nativePool(CronetNativeByteBufferPool.DEFAULT)
        arrayPool(ByteArrayPool.DEFAULT)

        maxConcurrentRequests(50)
        maxConcurrentRequestsPerHost(10)

        followRedirects(true)
        followSslRedirects(true)

        addMetricListener { metric: HttpMetrics, _, _ ->
            Metrics.addMetric(metric)
        }
    }

    addInterceptor { p ->
        try {
            p.proceed(p.request)
        } catch (e: Exception) {
            try {
                println("[KNet] [RETRY ${if (e is ConnectException) e.message else e.localizedMessage}]")
                p.proceed(p.request)
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }
}

val knetCronet = Knet.Build(cronet) {
    bufferPool(ByteArrayPool.DEFAULT)
}
```

## <a name="okhttp"></a> OkHTTP
If you're now using OkHttp, you can simply wrap KNet into OkHttp Interceptor... Easy, right?

```kotlin
val knetCronet = Knet.Build(cronet)
val okhttpInterceptor = KnetToOkHttpInterceptor(knetCronet)

val builder = OkHttpClient.Builder()
builder.addInterceptor(okhttpInterceptor)
val client = builder.build()
```


## <a name="soon"></a> Comming Soon
- Tests
- Exo
- Ktor
- OkHttp
- Flipper
- Stetho

## <a name="lic"></a> Licence
```
The MIT License (MIT)

Copyright (c) 2019 vk.com

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

package app.ghostly.core.model

/**
 * Russian apps that go around the VPN by default (Android, [SplitMode.BYPASS_SELECTED]).
 *
 * Banks, Gosuslugi, MAX, marketplaces and the like look at the address they come from: through the
 * tunnel they see a foreign IP, refuse to work, or report that address as a VPN server. Directly
 * they see the user's usual Russian IP. Browsers and Telegram are deliberately not here. Packages
 * that aren't installed are simply skipped; the user can untick any of these.
 */
object RuApps {
    val PACKAGES: Set<String> = setOf(
        // government
        "ru.rostel",                              // Госуслуги
        "ru.gosuslugi.goskey",                    // Госключ
        "ru.altarix.mos.pgu",                     // Госуслуги Москвы
        "com.gnivts.selfemployed",                // Мой налог
        "com.octopod.russianpost.client",         // Почта России
        // messengers and social
        "ru.oneme.app",                           // MAX
        "ru.ok.tamtam",                           // ТамТам
        "com.vkontakte.android",                  // ВКонтакте
        "com.vk.im",                              // VK Мессенджер
        "com.vk.vkvideo",                         // VK Видео
        "com.uma.musicvk",                        // VK Музыка
        "ru.ok.android",                          // Одноклассники
        "ru.mail.mailapp",                        // Почта Mail.ru
        "ru.zen.android",                         // Дзен
        // banks and payments
        "ru.sberbankmobile",                      // СберБанк
        "com.idamob.tinkoff.android",             // Т-Банк
        "ru.tinkoff.investing",                   // Т-Инвестиции
        "ru.vtb24.mobilebanking.android",         // ВТБ
        "ru.alfabank.mobile.android",             // Альфа-Банк
        "ru.gazprombank.android.mobilebank.app",  // Газпромбанк
        "ru.raiffeisennews",                      // Райффайзенбанк
        "com.openbank",                           // Открытие
        "ru.letobank.Prometheus",                 // Почта Банк
        "ru.sovcomcard.halva.v1",                 // Халва
        "ru.mts.money",                           // МТС Банк
        "ru.ozon.fintech.finance",                // Ozon Банк
        "logo.com.mbanking",                      // ПСБ
        "ru.rshb.dbo",                            // Россельхозбанк
        "ru.rosbank.android",                     // Росбанк
        "ru.yoo.money",                           // ЮMoney
        "ru.nspk.mirpay",                         // Mir Pay
        "ru.nspk.sbpay",                          // СБПэй
        "com.yandex.bank",                        // Яндекс Пэй
        // marketplaces and shops
        "ru.ozon.app.android",                    // Ozon
        "com.wildberries.ru",                     // Wildberries
        "ru.beru.android",                        // Яндекс Маркет
        "ru.megamarket.marketplace",              // Мегамаркет
        "com.avito.android",                      // Авито
        "ru.instamart",                           // Купер
        "ru.sbcs.store",                          // Самокат
        "ru.foodfox.client",                      // Яндекс Еда
        "com.yandex.lavka",                       // Яндекс Лавка
        "ru.pyaterochka.app.browser",             // Пятёрочка
        "com.icemobile.lenta.prod",               // Лента
        "ru.dns.shop.android",                    // DNS
        "com.lamoda.lite",                        // Lamoda
        "ru.cian.main",                           // ЦИАН
        "ru.domclick.mortgage",                   // Домклик
        "ru.hh.android",                          // hh.ru
        // services, maps, transport
        "ru.yandex.taxi",                         // Яндекс Go
        "ru.yandex.yandexmaps",                   // Яндекс Карты
        "ru.yandex.yandexnavi",                   // Яндекс Навигатор
        "ru.yandex.disk",                         // Яндекс Диск
        "ru.yandex.mail",                         // Яндекс Почта
        "ru.dublgis.dgismobile",                  // 2ГИС
        "ru.rzd.pass",                            // РЖД Пассажирам
        // operators
        "ru.mts.mymts",                           // Мой МТС
        "ru.beeline.services",                    // Билайн
        "ru.megafon.mlk",                         // МегаФон
        "ru.tele2.mytele2",                       // t2
        // video and music (licensed for Russia only)
        "ru.yandex.music",                        // Яндекс Музыка
        "ru.kinopoisk",                           // Кинопоиск
        "ru.rutube.app",                          // Rutube
        "ru.ivi.client",                          // Иви
        "ru.more.play",                           // Okko
        "ru.rt.video.app.mobile",                 // Wink
        "com.zvooq.openplay",                     // Звук
    )
}

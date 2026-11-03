# Mimari karar kayıtları (ADR)

Bu klasör demonun mimarisini biçimlendiren kararları ve gerekçelerini tutar. Her kayıt bir karardır: bağlamı,
değerlendirilen seçenekleri, kararı, sonuçlarını (kazandıklarımız ve bedeli) ve kararın hâlâ geçerli olduğunu
gösteren denetimi yazar. Kayıtlar değiştirilmez; karar değişirse yeni bir ADR eskisinin yerini alır ve eskisinin
durumu "Yerini aldı: ADR-NNNN" olur.

| No | Karar | Durum | Tarih |
|---|---|---|---|
| [0001](0001-camunda-system-of-record.md) | İstisna vakasının kaydı Camunda'dadır | Kabul edildi | 2026-11-02 |
| [0002](0002-no-dual-write.md) | İkili yazım (dual write) yok; olaylar outbox'tan ve süreçten çıkar | Kabul edildi | 2026-11-02 |
| [0003](0003-protobuf-rest-avro-events.md) | REST gövdeleri protobuf, alan olayları Avro | Kabul edildi | 2026-11-02 |
| [0004](0004-ci-runner-policy.md) | PR'lar GitHub-hosted, main'e push self-hosted runner'da | Kabul edildi | 2026-11-03 |
| [0005](0005-exception-api-single-replica.md) | İstisna API'si tek kopya ve `Recreate` | Kabul edildi | 2026-11-02 |

ADR-0001, 0002, 0003 ve 0005'in uygulaması `app/payment-exception-api` modülündedir; ADR-0004'ün uygulaması
`.github/workflows/ci.yml` içindedir. İkisi de [PR #4](https://github.com/COPUR/demo-camunda-k8s/pull/4) ile
gelir; o PR birleşene kadar `main`'de bu dosyalar yoktur.

## Yeni bir ADR

[`template.md`](template.md) dosyasını `NNNN-kisa-ad.md` adıyla kopyalayın, numarayı bir artırın ve bu tabloya bir
satır ekleyin. Bir ADR'yi şu durumlarda yazın: karar birden fazla bileşeni etkiliyorsa, geri almak pahalıysa ya da
bir sonraki kişi "neden böyle?" diye soracaksa.

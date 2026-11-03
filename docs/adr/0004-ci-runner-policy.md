# ADR-0004: PR'lar GitHub-hosted, main'e push self-hosted runner'da

- **Durum:** Kabul edildi
- **Tarih:** 2026-11-03
- **Kapsam:** `.github/workflows/ci.yml` (`lint` ve `java` işleri), depo değişkeni `vars.CI_RUNNER`

## Bağlam

CI başlangıçta her işi `vars.CI_RUNNER`'ın adlandırdığı self-hosted runner'da koşuyordu: sunanın MacBook'undaki
bir Lima sanal makinesi (etiket `fbx-ci`). PR #4'ün bağımsız denetimi bunu yüksek bulgu olarak işaretledi:

- Bir pull request iş akışı dosyasını değiştirebilir; self-hosted runner'da bu, PR'ı açanın kişisel bir makinede
  kod çalıştırması demektir.
- Checkout token'ı (`persist-credentials: true`) çalışma dizininde kalıyordu.
- Depo ileride public yapılabilir; o zaman herkes PR açabilir.

Öte yandan self-hosted runner'ın değeri vardır: main'deki derlemeyi sunanın kendi ortamına yakın bir makinede
doğrular ve GitHub-hosted dakikaları harcamaz.

## Değerlendirilen seçenekler

1. **Her şey self-hosted.** Bedeli: PR'dan gelen kod kişisel makinede koşar; public depoda kabul edilemez.
2. **Her şey GitHub-hosted.** En güvenli. Bedeli: self-hosted makinedeki doğrulama kaybolur.
3. **PR'lar GitHub-hosted, yalnızca main'e push self-hosted.** Güvenilmeyen kod geçici bir makinede koşar;
   main'e yalnızca birleşmiş kod gelir.

## Karar

Seçenek 3. Kullanıcı bunu 2026-11-03'te bir karar kartında seçti. Her iş şu ifadeyle seçilir:

```yaml
runs-on: ${{ (github.event_name == 'push' && vars.CI_RUNNER) || 'ubuntu-latest' }}
```

`vars.CI_RUNNER` boşsa push da GitHub-hosted'da koşar. Her checkout `persist-credentials: false` taşır; iş akışı
izinleri `contents: read`. Üçüncü taraf eylemler commit SHA'sıyla sabitlenir.

## Sonuçlar

**Kazandıklarımız**

- PR'dan gelen kod kişisel makineye ulaşmaz; hiçbir iş checkout token'ını tutmaz.
- Main, self-hosted makinede de doğrulanır.

**Bedeli**

- İki ortam: bir PR GitHub-hosted'da yeşilken main'de self-hosted'da kırılabilir (araç sürümü, disk, ağ farkı).
- Bir PR hâlâ `ci.yml`'i değiştirip `push` koşulunu kaldırabilir; bu değişiklik birleşirse self-hosted'da koşar.
  Depo public yapılmadan önce ya self-hosted runner kaldırılmalı ya da dış katkıcıların her iş akışı koşusu onay
  beklemeli (Settings > Actions).

## Denetim

- `ci.yml`'deki her işin `runs-on` satırı yukarıdaki ifadeyle aynı; `persist-credentials: false` her checkout'ta.
- 9825c41'den sonraki PR koşularında işler `ubuntu-latest`'te görünür (ör. run 41614514277).

## Ne zaman yeniden bakılır

Depo public yapılırken (yukarıdaki koşul zorunlu hâle gelir) ya da kümeye kurulum testi (kind duman testi, T3)
self-hosted makinenin kaynaklarına ihtiyaç duyarsa.

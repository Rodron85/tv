# Forreta TV

Ver tudo sem gastar um tostão.

- `public/` – a app (web). O Cloudflare (Worker **wild-field-f05c**) publica-a sozinho sempre que há alterações.
  Link: https://wild-field-f05c.ronnieandrodrigues.workers.dev
- `android/` – a app para a TV (TCL / Google TV / Android TV). O GitHub constrói o APK sozinho (separador **Actions**).

## Instalar na TV
1. Na TV, instala a app **Downloader** (grátis, Play Store).
2. Nas definições da TV, autoriza o Downloader a instalar apps de fontes desconhecidas.
3. No Downloader escreve:
   `https://github.com/Rodron85/tv/releases/latest/download/ForretaTV.apk`
4. Instala. A **Forreta TV** aparece nas apps da TV com o F vermelho.

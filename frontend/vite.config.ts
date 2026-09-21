import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'
import { defineConfig } from 'vitest/config'

// O aplicativo e a API vivem na mesma origem: em produção o build é copiado para dentro do jar
// e o Spring o serve; em desenvolvimento o servidor do Vite faz proxy de /api para a aplicação
// na porta 8080, então o cliente nunca conhece outra origem e não há CORS a configurar.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // A versão nova espera o operador mandar atualizar, em vez de recarregar as abas sozinha:
      // recarregar no meio de uma venda custaria a venda.
      registerType: 'prompt',
      // Com esta extensão o servidor já responde application/json, que o navegador aceita como
      // manifest; a extensão webmanifest sairia como binário, porque o servidor não a conhece.
      manifestFilename: 'manifest.json',
      manifest: {
        name: 'Caixa Simples',
        short_name: 'Caixa Simples',
        description: 'Caixa e ponto de venda para pequenos negócios',
        lang: 'pt-BR',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'any',
        background_color: '#ffffff',
        theme_color: '#1f5f4a',
        icons: [
          { src: '/icones/icone-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icones/icone-512.png', sizes: '512x512', type: 'image/png' },
          {
            src: '/icones/icone-maskable-512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        // Só o shell entra no cache do service worker: a página, os scripts, os estilos, os
        // ícones e o manifest. Dado de negócio nunca é guardado pelo service worker; quando a
        // operação offline chegar, ela terá um armazenamento próprio, com fila e estados
        // explícitos, e não um cache de respostas HTTP.
        globPatterns: ['**/*.{js,css,html,svg,png,json}'],
        // Rota do cliente sem rede recebe o shell; /api nunca, porque a resposta de um erro de
        // rede na API tem de chegar ao código que chamou, não ser trocada por HTML.
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
      },
    }),
  ],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
  },
})

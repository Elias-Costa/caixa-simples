import { useSyncExternalStore } from 'react'

/**
 * O mesmo corte do CSS: a partir de 600px a barra de navegação é uma linha, e abaixo dele é o
 * celular. Escrito uma vez só, para a tela e o estilo não discordarem na borda.
 */
const DO_TABLET_EM_DIANTE = '(min-width: 600px)'

function consulta(): MediaQueryList | undefined {
  return typeof window.matchMedia === 'function' ? window.matchMedia(DO_TABLET_EM_DIANTE) : undefined
}

function assinar(avisar: () => void): () => void {
  const lista = consulta()
  lista?.addEventListener('change', avisar)
  return () => lista?.removeEventListener('change', avisar)
}

/**
 * Se a tela tem a largura de um celular, acompanhando a rotação e o redimensionamento. Sem
 * matchMedia, como no ambiente de teste, a tela conta como larga e nada muda.
 */
export function useCelular(): boolean {
  return useSyncExternalStore(assinar, () => consulta()?.matches === false)
}

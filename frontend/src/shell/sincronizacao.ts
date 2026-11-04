import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { aoMudarConexao, comServidor, estadoDaConexao } from '../api/conexao'
import { sincronizacao } from '../api/sincronizacao'
import { hojeNoBalcao } from '../dataDoBalcao'
import { enviarFila } from '../offline/envio'
import { aoMudarFila, gestoPendente, listarGestos, sessaoPodeUsarAFila } from '../offline/fila'
import { marcarSessaoEncerrada } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'

/** Enquanto houver gesto sem resultado e o servidor responder, a fila é tentada de novo. */
export const INTERVALO_DE_REPETICAO = 30_000

/**
 * Enquanto o navegador tem rede e o servidor não responde, o aparelho pergunta de novo a cada
 * intervalo: sem nenhuma chamada, nada diria que ele voltou. A pergunta é a identidade, que já
 * existe, é leve e renova o token.
 */
export const INTERVALO_DA_SONDAGEM = 15_000

export type Sincronizacao = {
  /** Uma rodada de envio está em curso nesta aba. */
  enviando: boolean
  /** Gestos ainda sem resultado do servidor: na fila, em envio ou com falha. */
  porEnviar: number
  comFalha: number
  /** Revisões não conferidas: as deste aparelho e, para o administrador, as da Conta inteira. */
  emRevisao: number
  /** Muda a cada rodada que gravou algum resultado; as telas recarregam quando muda. */
  rodada: number
  enviarAgora: () => void
  /** Conta de novo a fila e relê a lista da Conta, depois de uma conferência. */
  atualizar: () => void
}

export const SincronizacaoContext = createContext<Sincronizacao>({
  enviando: false, porEnviar: 0, comFalha: 0, emRevisao: 0, rodada: 0,
  enviarAgora: () => undefined, atualizar: () => undefined,
})

export function useSincronizacao(): Sincronizacao {
  return useContext(SincronizacaoContext)
}

type ContagemLocal = { porEnviar: number; comFalha: number; emRevisao: string[] }

/**
 * O envio automático da fila e as contagens do cabeçalho, montados uma vez no shell.
 *
 * A fila sai sozinha ao abrir o aplicativo, quando o servidor volta a responder, a cada gesto
 * gravado com servidor e a cada {@link INTERVALO_DE_REPETICAO} enquanto houver gesto sem resultado;
 * "Enviar agora" dispara na hora. Sem resposta do servidor, a sondagem pergunta por ele a cada
 * {@link INTERVALO_DA_SONDAGEM} e logo que a rede do navegador volta. Toda rodada que o servidor
 * respondeu relê a identidade, porque a Conta pode ter passado a exigir algo, como o NSU no cartão,
 * que a venda sem rede confere antes de gravar. Se o servidor recusa o token, a sessão local
 * termina como na abertura do aplicativo, e a fila fica para o próximo login. O administrador vê
 * também a lista de revisões da Conta, relida ao abrir, na volta do servidor, depois de cada envio
 * e a cada troca de tela.
 */
export function useEnvioAutomatico(identidade: Identidade | null, sair: () => void,
  caminho: string, relerIdentidade: () => Promise<unknown>): Sincronizacao {
  const [enviando, setEnviando] = useState(false)
  const [local, setLocal] = useState<ContagemLocal>({ porEnviar: 0, comFalha: 0, emRevisao: [] })
  const [daConta, setDaConta] = useState<string[]>([])
  const [rodada, setRodada] = useState(0)
  const emCurso = useRef(false)
  const deNovo = useRef(false)
  const temPendencia = useRef(false)
  const quem = identidade ? `${identidade.contaId}:${identidade.usuarioId}` : null
  const administrador = identidade?.perfil === 'ADMIN'

  const contar = useCallback(async () => {
    if (!('indexedDB' in globalThis) || !sessaoPodeUsarAFila()) return
    try {
      const gestos = await listarGestos()
      temPendencia.current = gestos.some(gestoPendente)
      setLocal({
        porEnviar: gestos.filter(gestoPendente).length,
        comFalha: gestos.filter((gesto) => gesto.estado === 'failed').length,
        emRevisao: gestos.filter((gesto) => gesto.estado === 'needs_review' && !gesto.conferidoEm)
          .map((gesto) => gesto.operacaoId),
      })
    } catch {
      // A sessão mudou no meio da leitura; a próxima contagem acerta.
    }
  }, [])

  const lerDaConta = useCallback(async () => {
    if (!administrador || !comServidor()) return
    try {
      const { pendentes } = await sincronizacao.revisoes(hojeNoBalcao())
      setDaConta(pendentes.map((revisao) => revisao.operacaoId))
    } catch {
      // Sem rede ou com a sessão trocada, fica a última contagem.
    }
  }, [administrador])

  const enviar = useCallback(async () => {
    // O pedido que chega durante uma rodada vira mais uma volta dela, e não uma rodada paralela.
    if (emCurso.current) {
      deNovo.current = true
      return
    }
    emCurso.current = true
    setEnviando(true)
    let respondida = false
    let recusada = false
    try {
      do {
        deNovo.current = false
        const rodadaDeEnvio = await enviarFila()
        if (rodadaDeEnvio.respondida) respondida = true
        if (rodadaDeEnvio.resolvidos > 0) setRodada((anterior) => anterior + 1)
        if (rodadaDeEnvio.desfecho === 'sessao-recusada') {
          recusada = true
          marcarSessaoEncerrada()
          sair()
          return
        }
      } while (deNovo.current && comServidor())
    } catch {
      // A sessão mudou durante a rodada; o que ficou em envio vira falha na próxima.
    } finally {
      emCurso.current = false
      setEnviando(false)
      void contar()
      void lerDaConta()
      if (respondida && !recusada) void relerIdentidade()
    }
  }, [contar, lerDaConta, sair, relerIdentidade])

  useEffect(() => {
    if (!quem) return
    queueMicrotask(() => {
      void contar()
      if (comServidor()) void enviar()
    })
  }, [quem, contar, enviar])

  // A volta do servidor, pela rede do navegador ou pela primeira chamada que respondeu.
  useEffect(() => aoMudarConexao(() => {
    if (!comServidor()) return
    void enviar()
    void lerDaConta()
  }), [enviar, lerDaConta])

  useEffect(() => {
    const voltouARede = () => {
      if (estadoDaConexao() === 'sem-servidor') void relerIdentidade()
    }
    window.addEventListener('online', voltouARede)
    const sondagem = window.setInterval(() => {
      if (estadoDaConexao() === 'sem-servidor') void relerIdentidade()
    }, INTERVALO_DA_SONDAGEM)
    return () => {
      window.removeEventListener('online', voltouARede)
      window.clearInterval(sondagem)
    }
  }, [relerIdentidade])

  useEffect(() => aoMudarFila((mudanca) => {
    void contar()
    if (mudanca === 'gesto-novo' && comServidor()) void enviar()
  }), [contar, enviar])

  useEffect(() => {
    const repeticao = window.setInterval(() => {
      if (comServidor() && temPendencia.current) void enviar()
    }, INTERVALO_DE_REPETICAO)
    return () => window.clearInterval(repeticao)
  }, [enviar])

  // Outros aparelhos enviam revisões a qualquer hora; o administrador vê a contagem nova a cada tela.
  useEffect(() => { queueMicrotask(() => void lerDaConta()) }, [lerDaConta, caminho])

  const emRevisao = new Set([...local.emRevisao, ...(administrador ? daConta : [])]).size
  const enviarAgora = useCallback(() => { void enviar() }, [enviar])
  const atualizar = useCallback(() => {
    void contar()
    void lerDaConta()
  }, [contar, lerDaConta])

  return useMemo(() => ({
    enviando, porEnviar: local.porEnviar, comFalha: local.comFalha, emRevisao, rodada, enviarAgora, atualizar,
  }), [enviando, local, emRevisao, rodada, enviarAgora, atualizar])
}

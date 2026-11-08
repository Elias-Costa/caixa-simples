import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { chamarApi, ErroDaApi, SessaoAlterada } from '../api/cliente'
import { SessaoContext, type Sessao } from './contexto'
import type { Identidade } from './Identidade'
import { copiarCadastroSeFaltar, prepararCadastroLocal } from './prepararCadastroLocal'
import {
  aoVencerToken,
  CHAVE_DA_SESSAO,
  fixarSessaoDaAba,
  gravarIdentidade,
  gravarToken,
  lerIdentidade,
  lerToken,
  limparMarcasDaEntrada,
  limparSessao,
  marcarSessaoEncerrada,
  marcarSessaoVencida,
  marcarVoltaPorOutraAba,
  sessaoAtual,
  sessaoDaAba,
  tirarMarcaDeEntradaEmOutraAba,
} from './armazenamento'
import { RelogioAdiantado, tokenExpirado } from './token'

/**
 * De quanto em quanto tempo o aplicativo aberto confere se o token venceu. O intervalo é o caminho da
 * tela parada; um temporizador não anda com o aparelho dormindo, e por isso a volta à tela, a volta
 * da rede e a recusa da fila conferem também, na hora.
 */
const INTERVALO_DA_CONFERENCIA = 30_000

/**
 * A identidade com que o aplicativo abre: a guardada no dispositivo, se o token guardado ainda
 * vale. É o que faz o aplicativo abrir sem rede: ninguém é perguntado, e o token será usado
 * quando a rede voltar. Token expirado é descartado, e o caminho é o login, que diz por quê.
 */
function identidadeInicial(): Identidade | null {
  const token = lerToken()
  if (!token || tokenExpirado(token)) {
    if (token) marcarSessaoVencida()
    const sessao = sessaoAtual()
    if (!sessaoDaAba() && sessao) fixarSessaoDaAba(sessao)
    limparSessao()
    return null
  }
  const sessao = sessaoAtual()
  if (!sessao || (sessaoDaAba() && sessaoDaAba() !== sessao)) return null
  fixarSessaoDaAba(sessao)
  return lerIdentidade()
}

export function SessaoProvider({ children }: { children: ReactNode }) {
  const [identidade, setIdentidade] = useState<Identidade | null>(identidadeInicial)
  const tentativaDeEntrada = useRef(0)

  const sair = useCallback(() => {
    tentativaDeEntrada.current++
    limparSessao()
    setIdentidade(null)
  }, [])

  // A identidade só é gravada e mostrada na sessão de login desta aba. Outra aba pode ter entrado em
  // outra Conta, e o que esta aba recebeu não pode chegar à identidade dela: o cliente HTTP já recusa a
  // resposta de outra sessão, e esta guarda vale mesmo sem isso.
  const atualizarIdentidade = useCallback((atual: Identidade, sessao: string) => {
    const daAba = () => sessaoAtual() === sessao && sessaoDaAba() === sessao
    if (!daAba()) return
    gravarIdentidade(atual, sessao)
    if (daAba()) setIdentidade(atual)
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const tentativa = ++tentativaDeEntrada.current
    const sessaoAnterior = sessaoAtual()
    const login = await chamarApi<{ token: string }>('/api/auth/login', {
      metodo: 'POST',
      corpo: { email, senha },
      autenticado: false,
    })
    if (tentativa !== tentativaDeEntrada.current || sessaoAtual() !== sessaoAnterior) {
      throw new SessaoAlterada()
    }
    if (tokenExpirado(login.token)) throw new RelogioAdiantado()
    const sessaoCriada = gravarToken(login.token)
    if (sessaoAtual() !== sessaoCriada) throw new SessaoAlterada()
    let atual: Identidade
    try {
      atual = await chamarApi<Identidade>('/api/auth/eu')
    } catch (falha) {
      // Sem a identidade o aplicativo não abre, e a pessoa vai repetir o login de qualquer jeito. O token
      // que ficasse faria a página de apagar os dados dizer que há uma sessão aberta, sem Sair para tocar.
      // Só sai a sessão que este login criou: outra aba pode ter aberto a dela enquanto a chamada corria.
      if (sessaoAtual() === sessaoCriada) limparSessao()
      throw falha
    }
    if (tentativa !== tentativaDeEntrada.current || sessaoAtual() !== sessaoCriada) {
      throw new SessaoAlterada()
    }
    limparMarcasDaEntrada()
    atualizarIdentidade(atual, sessaoCriada)
    try {
      await prepararCadastroLocal(atual)
    } catch {
      // A falha da cópia local não invalida uma autenticação que o servidor já aceitou.
    }
  }, [atualizarIdentidade])

  // Outra aba pode trocar a Conta no armazenamento compartilhado. Esta aba deixa de exibir
  // imediatamente a identidade anterior; as chamadas em voo são recusadas pelo cliente HTTP. A entrada diz
  // por quê: a chave trocada por outra é alguém que entrou na outra aba; a chave apagada, quando a sessão
  // era a desta aba, é alguém que saiu nela. Uma sessão de outra aba que termina não é a desta, e só deixa
  // de valer a frase de quem entrou.
  useEffect(() => {
    const outraAbaMudouSessao = (evento: StorageEvent) => {
      if (evento.key !== CHAVE_DA_SESSAO && evento.key !== null) return
      if (evento.key !== null && evento.newValue !== null) marcarVoltaPorOutraAba('entrou')
      else if (evento.key !== null && evento.oldValue !== null && evento.oldValue === sessaoDaAba()) {
        marcarVoltaPorOutraAba('saiu')
      } else tirarMarcaDeEntradaEmOutraAba()
      tentativaDeEntrada.current++
      setIdentidade(null)
    }
    window.addEventListener('storage', outraAbaMudouSessao)
    return () => window.removeEventListener('storage', outraAbaMudouSessao)
  }, [])

  // Pergunta ao servidor quem está operando: a resposta renova o token, atualiza nome, perfil e o
  // que a Conta exige, como o NSU no cartão, e derruba quem foi inativado, porque o servidor
  // responde 401. Sem rede, fica o que estava guardado. A resposta que chega depois de outro login
  // ou de outra Conta nesta aba é descartada. A resposta é também a volta do servidor: a pergunta sai
  // com a rede que volta, pela sondagem enquanto ele não responde e depois de cada envio respondido, e
  // a cópia do cadastro que ainda falta é feita aí.
  const relerIdentidade = useCallback(async (): Promise<Identidade | undefined> => {
    if (!lerToken()) return undefined
    const tentativa = tentativaDeEntrada.current
    const sessao = sessaoAtual()
    const mesmaSessao = () => tentativa === tentativaDeEntrada.current && sessaoAtual() === sessao
    try {
      const atual = await chamarApi<Identidade>('/api/auth/eu')
      if (!mesmaSessao() || !sessao) return undefined
      atualizarIdentidade(atual, sessao)
      void copiarCadastroSeFaltar(atual).catch(() => undefined)
      return atual
    } catch (falha) {
      if (mesmaSessao() && falha instanceof ErroDaApi && falha.status === 401) {
        marcarSessaoEncerrada()
        sair()
      }
      return undefined
    }
  }, [sair, atualizarIdentidade])

  // O token vencido encerra a sessão da mesma forma em todo caminho: a tela parada, a volta à tela, a
  // rede que volta e a fila que recusa. O vencimento é julgado pelo relógio do aparelho, porque sem rede
  // não há outro; a fila fica guardada, por Conta e Usuário, para quando essa pessoa entrar de novo.
  const autenticado = identidade !== null
  useEffect(() => {
    if (!autenticado) return
    const conferir = () => {
      const token = lerToken()
      if (!token || !tokenExpirado(token)) return
      marcarSessaoVencida()
      sair()
    }
    const aoVoltarATela = () => { if (document.visibilityState === 'visible') conferir() }
    const conferencia = window.setInterval(conferir, INTERVALO_DA_CONFERENCIA)
    document.addEventListener('visibilitychange', aoVoltarATela)
    window.addEventListener('online', conferir)
    const desligarVencimento = aoVencerToken(conferir)
    return () => {
      window.clearInterval(conferencia)
      document.removeEventListener('visibilitychange', aoVoltarATela)
      window.removeEventListener('online', conferir)
      desligarVencimento()
    }
  }, [autenticado, sair])

  // Na abertura com sessão guardada, a pergunta acima, e a cópia local do cadastro com a resposta.
  // Depois, o envio da fila e a sondagem do servidor a repetem; o login faz a mesma pergunta por
  // conta própria.
  useEffect(() => {
    let descartado = false
    queueMicrotask(() => void relerIdentidade().then((atual) => {
      if (!descartado && atual) void prepararCadastroLocal(atual).catch(() => undefined)
    }))
    return () => {
      descartado = true
    }
  }, [relerIdentidade])

  const sessao = useMemo<Sessao>(
    () => ({ identidade, entrar, sair, atualizarIdentidade, relerIdentidade }),
    [identidade, entrar, sair, atualizarIdentidade, relerIdentidade],
  )

  return <SessaoContext.Provider value={sessao}>{children}</SessaoContext.Provider>
}

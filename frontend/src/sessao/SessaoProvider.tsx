import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { chamarApi, ErroDaApi, SessaoAlterada } from '../api/cliente'
import { cadastro } from '../api/cadastro'
import { SessaoContext, type Sessao } from './contexto'
import type { Identidade } from './Identidade'
import {
  CHAVE_DA_SESSAO,
  fixarSessaoDaAba,
  gravarIdentidade,
  gravarToken,
  lerIdentidade,
  lerToken,
  limparSessao,
  sessaoAtual,
  sessaoDaAba,
} from './armazenamento'
import { tokenExpirado } from './token'

/**
 * A identidade com que o aplicativo abre: a guardada no dispositivo, se o token guardado ainda
 * vale. É o que faz o aplicativo abrir sem rede: ninguém é perguntado, e o token será usado
 * quando a rede voltar. Token expirado é descartado, e o caminho é o login.
 */
function identidadeInicial(): Identidade | null {
  const token = lerToken()
  if (!token || tokenExpirado(token)) {
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

async function prepararCadastroLocal(atual: Identidade): Promise<void> {
  if (!('indexedDB' in globalThis) || !atual.contaId || !atual.usuarioId) return
  // O primeiro login ADMIN já copiou o catálogo no servidor. Guardar as listas recebidas evita
  // aplicar os modelos outra vez e permite consultar Clientes quando o balcão abre sem rede.
  await Promise.all([cadastro.produtos(), cadastro.clientes(), cadastro.clientesInativos()])
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
    const sessaoCriada = gravarToken(login.token)
    if (sessaoAtual() !== sessaoCriada) throw new SessaoAlterada()
    const atual = await chamarApi<Identidade>('/api/auth/eu')
    if (tentativa !== tentativaDeEntrada.current || sessaoAtual() !== sessaoCriada) {
      throw new SessaoAlterada()
    }
    atualizarIdentidade(atual, sessaoCriada)
    try {
      await prepararCadastroLocal(atual)
    } catch {
      // A falha da cópia local não invalida uma autenticação que o servidor já aceitou.
    }
  }, [atualizarIdentidade])

  // Outra aba pode trocar a Conta no armazenamento compartilhado. Esta aba deixa de exibir
  // imediatamente a identidade anterior; as chamadas em voo são recusadas pelo cliente HTTP.
  useEffect(() => {
    const outraAbaMudouSessao = (evento: StorageEvent) => {
      if (evento.key === CHAVE_DA_SESSAO || evento.key === null) {
        tentativaDeEntrada.current++
        setIdentidade(null)
      }
    }
    window.addEventListener('storage', outraAbaMudouSessao)
    return () => window.removeEventListener('storage', outraAbaMudouSessao)
  }, [])

  // Ao abrir com sessão guardada, pergunta ao servidor quem está operando: a resposta renova o
  // token, atualiza nome e perfil e derruba quem foi inativado, porque o servidor responde 401.
  // Sem rede, fica o que estava guardado. Roda uma vez, na abertura; o login faz a mesma
  // pergunta por conta própria.
  useEffect(() => {
    if (!lerToken()) return
    let descartado = false
    const tentativa = tentativaDeEntrada.current
    const sessao = sessaoAtual()
    chamarApi<Identidade>('/api/auth/eu')
      .then((atual) => {
        if (descartado || tentativa !== tentativaDeEntrada.current || sessaoAtual() !== sessao) return
        if (sessao) atualizarIdentidade(atual, sessao)
        void prepararCadastroLocal(atual).catch(() => undefined)
      })
      .catch((falha: unknown) => {
        if (descartado || tentativa !== tentativaDeEntrada.current || sessaoAtual() !== sessao) return
        if (falha instanceof ErroDaApi && falha.status === 401) sair()
      })
    return () => {
      descartado = true
    }
  }, [sair, atualizarIdentidade])

  const sessao = useMemo<Sessao>(
    () => ({ identidade, entrar, sair, atualizarIdentidade }),
    [identidade, entrar, sair, atualizarIdentidade],
  )

  return <SessaoContext.Provider value={sessao}>{children}</SessaoContext.Provider>
}

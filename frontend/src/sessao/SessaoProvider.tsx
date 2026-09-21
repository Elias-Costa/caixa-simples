import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { chamarApi, ErroDaApi } from '../api/cliente'
import { SessaoContext, type Sessao } from './contexto'
import type { Identidade } from './Identidade'
import {
  gravarIdentidade,
  gravarToken,
  lerIdentidade,
  lerToken,
  limparSessao,
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
    limparSessao()
    return null
  }
  return lerIdentidade()
}

export function SessaoProvider({ children }: { children: ReactNode }) {
  const [identidade, setIdentidade] = useState<Identidade | null>(identidadeInicial)

  const sair = useCallback(() => {
    limparSessao()
    setIdentidade(null)
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const login = await chamarApi<{ token: string }>('/api/auth/login', {
      metodo: 'POST',
      corpo: { email, senha },
      autenticado: false,
    })
    gravarToken(login.token)
    const atual = await chamarApi<Identidade>('/api/auth/eu')
    gravarIdentidade(atual)
    setIdentidade(atual)
  }, [])

  // Ao abrir com sessão guardada, pergunta ao servidor quem está operando: a resposta renova o
  // token, atualiza nome e perfil e derruba quem foi inativado, porque o servidor responde 401.
  // Sem rede, fica o que estava guardado. Roda uma vez, na abertura; o login faz a mesma
  // pergunta por conta própria.
  useEffect(() => {
    if (!lerToken()) return
    let descartado = false
    chamarApi<Identidade>('/api/auth/eu')
      .then((atual) => {
        if (descartado) return
        gravarIdentidade(atual)
        setIdentidade(atual)
      })
      .catch((falha: unknown) => {
        if (descartado) return
        if (falha instanceof ErroDaApi && falha.status === 401) sair()
      })
    return () => {
      descartado = true
    }
  }, [sair])

  const sessao = useMemo<Sessao>(
    () => ({ identidade, entrar, sair }),
    [identidade, entrar, sair],
  )

  return <SessaoContext.Provider value={sessao}>{children}</SessaoContext.Provider>
}

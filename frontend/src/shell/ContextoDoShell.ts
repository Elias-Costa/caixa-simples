import { createContext, useContext } from 'react'

export type ContextoDoShell = {
  definirFaixa: (texto: string | null) => void
  /** A tela de venda avisa quando mostra uma venda aberta, que a recarga da versão nova fecharia. */
  definirVendaAberta: (aberta: boolean) => void
}

export const ContextoDoShellReact = createContext<ContextoDoShell>({
  definirFaixa: () => undefined,
  definirVendaAberta: () => undefined,
})

export function useContextoDoShell() { return useContext(ContextoDoShellReact) }

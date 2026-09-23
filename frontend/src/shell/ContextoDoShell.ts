import { createContext, useContext } from 'react'

export type ContextoDoShell = { definirFaixa: (texto: string | null) => void }

export const ContextoDoShellReact = createContext<ContextoDoShell>({ definirFaixa: () => undefined })

export function useContextoDoShell() { return useContext(ContextoDoShellReact) }

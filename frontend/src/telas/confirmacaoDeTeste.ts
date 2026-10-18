import { fireEvent, screen, within } from '@testing-library/react'

/** O diálogo de confirmação aberto; o nome dele é a pergunta. */
export function confirmacaoAberta(): Promise<HTMLElement> {
  return screen.findByRole('alertdialog')
}

/** Responde a confirmação aberta pelo botão com esse nome, a ação ou Voltar, e devolve o diálogo. */
export async function responderConfirmacao(botao: string): Promise<HTMLElement> {
  const dialogo = await confirmacaoAberta()
  fireEvent.click(within(dialogo).getByRole('button', { name: botao }))
  return dialogo
}

import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { useConfirmacao } from './useConfirmacao'

function Tela({ aoResponder }: { aoResponder: (sim: boolean) => void }) {
  const [confirmar, dialogo] = useConfirmacao()

  async function perguntar() {
    aoResponder(await confirmar({
      titulo: 'Cancelar a venda de R$ 6,50?',
      texto: 'O cancelamento não pode ser desfeito.',
      acao: 'Cancelar venda',
    }))
  }

  return <section>
    <button type="button" onClick={() => void perguntar()}>Cancelar venda</button>
    {dialogo}
  </section>
}

/** Abre a pergunta a partir do botão, com o foco nele, como faz o toque. */
async function abrirPergunta(aoResponder: (sim: boolean) => void): Promise<HTMLElement> {
  render(<Tela aoResponder={aoResponder} />)
  const botao = screen.getByRole('button', { name: 'Cancelar venda' })
  botao.focus()
  fireEvent.click(botao)
  return confirmacaoAberta()
}

describe('confirmação do aplicativo', () => {
  it('pergunta pelo título, diz a consequência e começa com o foco no Voltar', async () => {
    const dialogo = await abrirPergunta(vi.fn())

    expect(dialogo).toHaveAccessibleName('Cancelar a venda de R$ 6,50?')
    expect(dialogo).toHaveAccessibleDescription('O cancelamento não pode ser desfeito.')
    expect(screen.getByRole('button', { name: 'Voltar' })).toHaveFocus()
  })

  it('Voltar responde não, fecha o diálogo e devolve o foco a quem o abriu', async () => {
    const aoResponder = vi.fn()
    await abrirPergunta(aoResponder)

    await responderConfirmacao('Voltar')

    await vi.waitFor(() => expect(aoResponder).toHaveBeenCalledWith(false))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar venda' })).toHaveFocus()
  })

  it('o botão da ação responde sim', async () => {
    const aoResponder = vi.fn()
    await abrirPergunta(aoResponder)

    await responderConfirmacao('Cancelar venda')

    await vi.waitFor(() => expect(aoResponder).toHaveBeenCalledWith(true))
  })

  it('fechar sem resposta, como faz o Esc, vale como Voltar', async () => {
    const aoResponder = vi.fn()
    const dialogo = await abrirPergunta(aoResponder)

    act(() => { (dialogo as HTMLDialogElement).close() })

    await vi.waitFor(() => expect(aoResponder).toHaveBeenCalledWith(false))
  })
})

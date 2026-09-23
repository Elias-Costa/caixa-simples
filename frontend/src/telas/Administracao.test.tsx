import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { contas } from '../api/contas'
import { ErroDaApi } from '../api/cliente'
import { gravarToken } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { itensDoMenu } from '../shell/menu'
import { TelaDeConfiguracao } from './TelaDeConfiguracao'
import { TelaDeUsuarios } from './TelaDeUsuarios'

afterEach(() => vi.restoreAllMocks())

const admin: Identidade = {
  usuarioId: 'admin', nome: 'Ana', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Loja', estoqueHabilitado: false,
}

function ComSessaoEConfig() {
  const [identidade, atualizarIdentidade] = useState(admin)
  return <SessaoContext.Provider value={{
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade,
  }}>
    <p>Menu: {itensDoMenu(identidade).map((item) => item.rotulo).join(', ')}</p>
    <TelaDeConfiguracao />
  </SessaoContext.Provider>
}

describe('administração na tela', () => {
  it('liga o estoque e mostra o menu imediatamente', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'configuracao').mockResolvedValue({ estoqueHabilitado: false })
    const definir = vi.spyOn(contas, 'definirEstoque')
      .mockResolvedValue({ estoqueHabilitado: true })
    render(<ComSessaoEConfig />)

    fireEvent.click(await screen.findByRole('switch', { name: 'Ligar controle de estoque' }))
    await waitFor(() => expect(definir).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('switch', { name: 'Desligar controle de estoque' }))
      .toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText(/Menu:.*Estoque/)).toBeInTheDocument()
  })

  it('mostra a recusa por plano sem esconder a lista de usuários', async () => {
    vi.spyOn(contas, 'usuarios').mockResolvedValue([{
      id: 'admin', nome: 'Ana', perfil: 'ADMIN', ativo: true,
    }])
    vi.spyOn(contas, 'criarUsuario').mockRejectedValue(
      new ErroDaApi(409, 'Conflict', 'o plano admite um único usuário', {}),
    )
    render(<TelaDeUsuarios />)
    expect(await screen.findByText('Ana')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Bia' } })
    fireEvent.change(screen.getByLabelText('E-mail de entrada'), { target: { value: 'bia@exemplo.test' } })
    fireEvent.change(screen.getByLabelText('Senha inicial'), { target: { value: 'uma senha longa de teste' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar usuário' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('o plano admite um único usuário')
    expect(screen.getByText('Ana')).toBeInTheDocument()
  })
})

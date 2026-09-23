import { useCallback, useEffect, useState } from 'react'
import { contas } from '../api/contas'
import { sessaoAtual } from '../sessao/armazenamento'
import { useSessao } from '../sessao/useSessao'
import { erroDeCadastro } from './erroDeCadastro'

export function TelaDeConfiguracao() {
  const { identidade, atualizarIdentidade } = useSessao()
  const [habilitado, setHabilitado] = useState<boolean | null>(null)
  const [salvando, setSalvando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  const carregar = useCallback(async () => {
    try {
      const configuracao = await contas.configuracao()
      setHabilitado(configuracao.estoqueHabilitado)
      setErro(null)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  async function alternar() {
    if (habilitado === null || !identidade) return
    const sessao = sessaoAtual()
    if (!sessao) return
    setSalvando(true)
    setErro(null)
    try {
      const configuracao = await contas.definirEstoque(!habilitado)
      setHabilitado(configuracao.estoqueHabilitado)
      atualizarIdentidade({ ...identidade, estoqueHabilitado: configuracao.estoqueHabilitado }, sessao)
    } catch (falha) {
      setErro(erroDeCadastro(falha))
    } finally {
      setSalvando(false)
    }
  }

  return <section className="relatorios">
    <h2 className="titulo">Configuração da Conta</h2>
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    <section className="relatorios__cartao">
      <h3>Controle de estoque</h3>
      {habilitado === null ? <p>Carregando configuração...</p> : <>
        <p>{habilitado ? 'Ligado' : 'Desligado'}</p>
        <p>Quando ligado, vendas de produtos movimentam o saldo e o menu Estoque fica disponível.</p>
        <p>Depois do primeiro movimento de estoque, o controle não pode ser desligado.</p>
        <button className="botao" type="button" role="switch" aria-checked={habilitado}
          disabled={salvando} onClick={() => void alternar()}>
          {salvando ? 'Salvando...' : habilitado ? 'Desligar controle de estoque' : 'Ligar controle de estoque'}
        </button>
      </>}
    </section>
  </section>
}

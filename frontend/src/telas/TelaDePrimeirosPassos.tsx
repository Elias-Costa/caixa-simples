import { useNavigate } from 'react-router'
import { PrimeirosPassos } from './PrimeirosPassos'

/** Os primeiros passos reabertos pelo menu; ao terminar, a entrada leva ao caixa ou à venda. */
export function TelaDePrimeirosPassos() {
  const navegar = useNavigate()
  return <PrimeirosPassos aoTerminar={() => navegar('/')} />
}


export default function VDomClicks(sender){
    const sendThen = ctx => event => sender.send(ctx,"click","")
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation
    const transforms = ({onClick})
    return ({transforms})
}

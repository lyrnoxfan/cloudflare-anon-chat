// An anonymous session ID is not the session cookie. The short code is visual only.
export function identityLabel(nickname,id){
 const name=nickname||'匿名用户';
 return typeof id==='string'&&id ? `${name} · #${id.slice(0,8)}` : name;
}

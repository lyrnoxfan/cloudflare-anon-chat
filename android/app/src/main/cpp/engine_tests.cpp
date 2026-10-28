#include "engine.hpp"
#include <cstdlib>
#include "unicode.hpp"
#include <iostream>
using ql::J;
int main(){ql::Engine e;int checks=0;auto check=[&](bool b){if(!b){std::cerr<<"failed assertion "<<checks+1<<"\n";std::exit(1);}checks++;};auto rejects=[&](J j){try{e.command(j);return false;}catch(...){return true;}};
 auto gate=e.command({{"op","gate"},{"expired",true},{"hasSession",false}});check(gate["show"]==true&&gate["contentVisible"]==false);
 auto active=e.command({{"op","gate"},{"expired",false},{"hasSession",true}});check(active["show"]==false&&active["contentVisible"]==true);
 auto restored=e.command({{"op","gate"},{"expired",true},{"hasSession",true}});check(restored["show"]==false&&restored["contentVisible"]==true);
 auto initialSession=e.command({{"op","session_state"}});check(initialSession["phase"]=="unknown"&&initialSession["showGate"]&&!initialSession["contentVisible"]);
 e.command({{"op","session_required"}});check(e.command({{"op","session_state"}})["showGate"]==true);
 J expiredSession={{"id","123e4567-e89b-12d3-a456-426614174000"},{"nickname","小青"},{"expires",99999}};
 check(rejects({{"op","session_accept"},{"session",expiredSession},{"now",100000}}));check(e.command({{"op","session_state"}})["phase"]=="required");
 J validSession={{"id","123e4567-e89b-12d3-a456-426614174000"},{"nickname"," 小青 "},{"expires",200000}};
 auto accepted=e.command({{"op","session_accept"},{"session",validSession},{"now",100000}});check(accepted["phase"]=="active"&&accepted["nickname"]=="小青");
 auto sessionGate=e.command({{"op","session_state"}});check(sessionGate["active"]&& !sessionGate["showGate"]&&sessionGate["contentVisible"]);
 J malformedSession={{"id","not-a-uuid"},{"nickname","bad"},{"expires",200000}};check(rejects({{"op","session_accept"},{"session",malformedSession},{"now",100000}}));check(e.command({{"op","session_state"}})["phase"]=="active");
 e.command({{"op","session_required"}});check(e.command({{"op","session_state"}})["phase"]=="required");
 auto sessionRequest=e.command({{"op","session_request"},{"nickname"," Mock guest "}});check(sessionRequest["method"]=="POST"&&sessionRequest["path"]=="/api/session"&&sessionRequest["body"]["nickname"]=="Mock guest");check(e.command({{"op","session_state"}})["phase"]=="creating"&&e.command({{"op","session_state"}})["showGate"]);
 check(rejects({{"op","session_response"},{"status",401},{"body","{\"error\":\"请先输入昵称\"}"},{"now",100000}}));check(e.command({{"op","session_state"}})["phase"]=="required");
 auto retryRequest=e.command({{"op","session_request"},{"nickname"," Mock guest "}});check(retryRequest["body"]["nickname"]=="Mock guest");
 J sessionBody={{"id","123e4567-e89b-12d3-a456-426614174000"},{"nickname"," Mock guest "},{"expires",200000},{"limits",{{"nickname",32},{"sessionMs",604800000},{"text",4000},{"roomName",80},{"passwordMin",1},{"passwordMax",128},{"image",10485760},{"audio",10485760},{"video",104857600},{"file",52428800},{"members",8},{"grantMs",7200000},{"uploadMs",120000},{"callLeaseMs",45000}}},{"sfu",false}};
 auto responseAccepted=e.command({{"op","session_response"},{"status",200},{"body",sessionBody.dump()},{"now",100000}});check(responseAccepted["session"]["nickname"]=="Mock guest"&&responseAccepted["session"]["limits"]["nickname"]==32&&responseAccepted["session"]["sfu"]==false);check(e.command({{"op","session_state"}})["phase"]=="active");
 e.command({{"op","session_request"},{"nickname","Updated"}});check(e.command({{"op","session_state"}})["contentVisible"]);check(rejects({{"op","session_response"},{"status",429},{"body","{\"error\":\"操作过于频繁\"}"},{"now",100000}}));check(e.command({{"op","session_state"}})["phase"]=="active");
 e.command({{"op","session_request"},{"nickname","Updated"}});check(rejects({{"op","session_response"},{"status",200},{"body","{\"id\":\"bad\"}"},{"now",100000}}));check(e.command({{"op","session_state"}})["phase"]=="active");
 e.command({{"op","session_required"}});e.command({{"op","session_request"},{"nickname","Retry"}});e.command({{"op","session_failed"}});check(e.command({{"op","session_state"}})["phase"]=="required");
 check(e.command({{"op","reset"},{"room","lobby"}})["room"]=="lobby");
 check(rejects({{"op","reset"},{"room","../bad"}}));check(rejects({{"op","reset"},{"room","房间"}}));
 check(e.command({{"op","join"},{"password","a"}})["password"]=="a");check(rejects({{"op","join"},{"password"," "}}));
 check(ql::Engine::units("你好😀")==4);check(rejects({{"op","session"},{"nickname",std::string(33,'a')}}));
 check(e.command({{"op","send"},{"text","\"你好😀\n"},{"clientId","id-1"}})["text"]=="\"你好😀");
 check(rejects({{"op","send"},{"text",std::string(4001,'x')},{"clientId","id"}}));
 J a={{"id","b"},{"created",20},{"text","later"},{"deleted",0}},b={{"id","a"},{"created",10},{"text","first"},{"deleted",0}};
 e.command({{"op","history"},{"messages",J::array({a,b,a})}});auto snap=e.command({{"op","snapshot"}});check(snap["messages"].size()==2);check(snap["messages"][0]["id"]=="a");
 e.command({{"op","event"},{"event",{{"type","deleted"},{"id","a"}}}});e.command({{"op","history"},{"messages",J::array({b})}});check(e.command({{"op","snapshot"}})["messages"][0]["deleted"]==1);
 e.command({{"op","event"},{"event",{{"type","deleted"},{"id","unknown"}}}});e.command({{"op","history"},{"messages",J::array({{{"id","unknown"},{"created",30},{"text","stale"}}})}});check(e.command({{"op","snapshot"}})["messages"][2]["deleted"]==1);
 check(e.command({{"op","retry"},{"attempt",20}})["delay"]==30000);
 J u={{"op","upload"},{"size",100},{"mime","image/png"},{"filename","图.png"},{"clientId","id"}};check(e.command(u)["kind"]=="image");u["size"]=11*1024*1024;check(rejects(u));u["mime"]="application/octet-stream";check(e.command(u)["kind"]=="file");u["size"]=0;check(rejects(u));
 e.command({{"op","reset"},{"room","1234567890"}});check(e.command({{"op","snapshot"}})["messages"].empty());
 check(ql::utf8(ql::utf16("中文😀\n"))=="中文😀\n");
 for(auto bad:std::vector<std::string>{std::string("\xc0\x80",2),std::string("\xed\xa0\x80",3),std::string("\xf4\x90\x80\x80",4),std::string("\xf0",1)}){bool failed=false;try{ql::utf16(bad);}catch(...){failed=true;}check(failed);}
 bool failed=false;try{ql::utf8(std::u16string(1,0xd800));}catch(...){failed=true;}check(failed);
 check(ql::utf8(std::u16string(1,0))==std::string(1,0));
 std::cout<<checks<<" native assertions passed\n";
}

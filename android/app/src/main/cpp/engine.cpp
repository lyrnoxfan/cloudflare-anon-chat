#include "engine.hpp"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <stdexcept>
#include "unicode.hpp"
namespace ql {
size_t Engine::units(const std::string& s){
 return utf16(s).size();
}
std::string Engine::validText(const J& v,size_t max,size_t min){if(!v.is_string())throw std::runtime_error("请输入文字");auto s=v.get<std::string>();auto first=s.find_first_not_of(" \n\r\t");if(first==std::string::npos)throw std::runtime_error("内容不能为空");auto last=s.find_last_not_of(" \n\r\t");if(units(s)>max)throw std::runtime_error("内容超过长度限制");s=s.substr(first,last-first+1);if(units(s)<min)throw std::runtime_error("内容太短");return s;}
void Engine::merge(const J& m){if(!m.is_object()||!m.contains("id")||!m["id"].is_string()||!m.contains("created")||!m["created"].is_number_integer())throw std::runtime_error("消息格式异常");auto id=m["id"].get<std::string>();if(id.size()>80)throw std::runtime_error("消息标识异常");J copy=m;if(m.value("deleted",0)!=0)deleted.insert(id);if(deleted.count(id)){copy["deleted"]=1;copy["text"]=nullptr;}messages[id]=copy;}
J Engine::acceptSession(const J& s,int64_t current){
 if(!s.is_object())throw std::runtime_error("会话响应格式异常");
 auto id=s.find("id");if(id==s.end()||!id->is_string())throw std::runtime_error("会话标识异常");
 auto sid=id->get<std::string>();if(sid.size()!=36)throw std::runtime_error("会话标识异常");
 for(size_t i=0;i<sid.size();++i){if(i==8||i==13||i==18||i==23){if(sid[i]!='-')throw std::runtime_error("会话标识异常");}else if(!std::isxdigit(static_cast<unsigned char>(sid[i])))throw std::runtime_error("会话标识异常");}
 auto nickname=s.find("nickname");if(nickname==s.end())throw std::runtime_error("昵称格式异常");auto nick=validText(*nickname,32);
 auto expires=s.find("expires");if(expires==s.end()||!expires->is_number_integer())throw std::runtime_error("会话有效期异常");
 int64_t exp=expires->get<int64_t>();if(exp<=current)throw std::runtime_error("会话已过期");
 sessionId=sid;sessionNickname=nick;sessionExpires=exp;sessionPhase="active";
 J normalized=s;normalized["nickname"]=nick;
 return {{"phase",sessionPhase},{"id",sessionId},{"nickname",sessionNickname},{"expires",sessionExpires},{"session",normalized},{"showGate",false},{"contentVisible",true}};
}
J Engine::command(const J& in){auto op=in.at("op").get<std::string>();
 if(op=="session_state"){return {{"phase",sessionPhase},{"active",sessionPhase=="active"},{"showGate",sessionPhase!="active"},{"contentVisible",sessionPhase=="active"}};}
 if(op=="session_required"){sessionRequestPending=false;sessionPendingWasActive=false;sessionPhase="required";sessionId.clear();sessionNickname.clear();sessionExpires=0;return {{"phase",sessionPhase},{"showGate",true},{"contentVisible",false}};}
 if(op=="session_request"){
  if(sessionRequestPending)throw std::runtime_error("会话请求正在处理中");
  auto nick=validText(in.at("nickname"),32);sessionPendingWasActive=sessionPhase=="active";sessionRequestPending=true;if(!sessionPendingWasActive)sessionPhase="creating";
  return {{"method","POST"},{"path","/api/session"},{"body",{{"nickname",nick}}},{"showGate",!sessionPendingWasActive},{"contentVisible",sessionPendingWasActive}};
 }
 if(op=="session_failed"){if(sessionRequestPending&&!sessionPendingWasActive){sessionPhase="required";sessionId.clear();sessionNickname.clear();sessionExpires=0;}sessionRequestPending=false;sessionPendingWasActive=false;return {{"phase",sessionPhase},{"showGate",sessionPhase!="active"},{"contentVisible",sessionPhase=="active"}};}
 if(op=="session_response"){
  if(!sessionRequestPending)throw std::runtime_error("没有待处理的会话请求");
  try{
   auto status=in.at("status");if(!status.is_number_integer())throw std::runtime_error("会话响应状态异常");int code=status.get<int>();
   auto raw=in.at("body");if(!raw.is_string())throw std::runtime_error("会话响应格式异常");auto text=raw.get<std::string>();if(text.size()>65536)throw std::runtime_error("会话响应过大");
   J response=J::parse(text);
   if(code!=200){auto error=response.is_object()?response.value("error",std::string("会话请求失败（")+std::to_string(code)+"）"):std::string("会话请求失败（")+std::to_string(code)+"）";throw std::runtime_error(error);}
   if(!response.is_object()||!response.contains("limits")||!response["limits"].is_object()||!response.contains("sfu")||!response["sfu"].is_boolean())throw std::runtime_error("会话响应格式异常");
   if(!response["limits"].contains("nickname")||!response["limits"]["nickname"].is_number_integer()||response["limits"]["nickname"].get<int>()!=32||!response["limits"].contains("sessionMs")||!response["limits"]["sessionMs"].is_number_integer()||response["limits"]["sessionMs"].get<int64_t>()<=0)throw std::runtime_error("会话响应格式异常");
   auto accepted=acceptSession(response,in.at("now").get<int64_t>());
   sessionRequestPending=false;sessionPendingWasActive=false;
   return accepted;
  }catch(...){if(sessionRequestPending&&!sessionPendingWasActive){sessionPhase="required";sessionId.clear();sessionNickname.clear();sessionExpires=0;}sessionRequestPending=false;sessionPendingWasActive=false;throw;}
 }
 if(op=="session_accept")return acceptSession(in.at("session"),in.at("now").get<int64_t>());
 if(op=="gate"){bool show=in.value("expired",false)&&!in.value("hasSession",false);return {{"show",show},{"contentVisible",!show}};}
 if(op=="reset"){auto r=validText(in.at("room"),80);if(!std::all_of(r.begin(),r.end(),[](unsigned char c){return std::isalnum(c)||c=='-';})||std::any_of(r.begin(),r.end(),[](unsigned char c){return c>127;}))throw std::runtime_error("房号格式不正确");room=r;messages.clear();deleted.clear();members=J::array();return {{"room",room}};}
 if(op=="session")return {{"nickname",validText(in.at("nickname"),32)}};
 if(op=="room")return {{"name",validText(in.at("name"),80)},{"password",validText(in.at("password"),128)}};
 if(op=="join")return {{"password",validText(in.at("password"),128)}};
 if(op=="send")return {{"text",validText(in.at("text"),4000)},{"clientId",validText(in.at("clientId"),80)}};
 if(op=="upload"){auto size=in.at("size").get<int64_t>();auto mime=validText(in.at("mime"),100);auto kind=std::string("file");if(mime=="image/png"||mime=="image/jpeg"||mime=="image/webp"||mime=="image/gif")kind="image";else if(mime=="video/mp4"||mime=="video/webm"||mime=="video/quicktime")kind="video";else if(in.value("voice",false)){if(mime!="audio/mp4")throw std::runtime_error("录音类型异常");kind="audio";}int64_t cap=(kind=="video"?100:kind=="file"?50:10)*1024LL*1024LL;if(size<=0||size>cap)throw std::runtime_error("文件为空或超过大小限制");return {{"size",size},{"mime",mime},{"kind",kind},{"filename",validText(in.at("filename"),180)},{"clientId",validText(in.at("clientId"),80)},{"duration",in.value("duration",0.0)}};}
 if(op=="history"){for(auto& m:in.at("messages"))merge(m);return {{"count",messages.size()}};}
 if(op=="event"){const auto& e=in.at("event");auto type=e.value("type","");if(type=="message")merge(e.at("message"));else if(type=="deleted"){auto id=e.at("id").get<std::string>();deleted.insert(id);if(messages.count(id)){messages[id]["deleted"]=1;messages[id]["text"]=nullptr;}}else if(type=="presence")members=e.at("members");return {{"type",type}};}
 if(op=="snapshot"){J rows=J::array();std::vector<J> sorted;for(auto& [key,v]:messages)sorted.push_back(v);std::sort(sorted.begin(),sorted.end(),[](const J&a,const J&b){auto x=a.at("created").get<int64_t>(),y=b.at("created").get<int64_t>();return x==y?a.at("id").get<std::string>()<b.at("id").get<std::string>():x<y;});for(auto& m:sorted)rows.push_back(m);return {{"messages",rows},{"members",members},{"room",room}};}
 if(op=="retry"){int attempt=std::clamp(in.value("attempt",0),0,5);return {{"delay",std::min(30000,1000*(1<<attempt))}};}
 throw std::runtime_error("未知核心操作");
}
}

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
 if (op == "call_invitation") {
   const auto c = in.at("call");
   const auto self = in.at("self");
   if (!c.is_object() || !self.is_string() || self.get<std::string>().empty() ||
       !c.contains("id") || !c["id"].is_string() ||
       !c.contains("kind") || !c["kind"].is_string() ||
       !c.contains("owner") || !c["owner"].is_string() ||
       !c.contains("invited") || !c["invited"].is_boolean() ||
       !c.contains("joined") || !c["joined"].is_boolean())
     throw std::runtime_error("通话邀请格式无效");
   const auto id = c["id"].get<std::string>();
   const auto kind = c["kind"].get<std::string>();
   const auto owner = c["owner"].get<std::string>();
   if (id.empty() || id.size() > 80 ||
       !std::all_of(id.begin(), id.end(), [](unsigned char ch) {
         return std::isalnum(ch) || ch == '-';
       }) || (kind != "audio" && kind != "video") || owner.empty() || owner.size() > 80)
     throw std::runtime_error("通话邀请内容无效");
   std::string target;
   if (c.contains("target") && !c["target"].is_null()) {
     if (!c["target"].is_string() || c["target"].get<std::string>().empty() ||
         c["target"].get<std::string>().size() > 80)
       throw std::runtime_error("通话邀请对象无效");
     target = c["target"].get<std::string>();
   }
   const bool eligible = target.empty() || target == self.get<std::string>() || owner == self.get<std::string>();
   return {{"id", id}, {"kind", kind}, {"invited", c["invited"]},
           {"joined", c["joined"]}, {"eligible", eligible}};
 }
 if (op == "call_begin") {
   if (callPhase != "idle" && callPhase != "failed")
     throw std::runtime_error("已有通话正在进行");
   auto kind = validText(in.at("kind"), 8);
   if (kind != "audio" && kind != "video")
     throw std::runtime_error("通话类型无效");
   callKind = kind;
   callId.clear();
   callPhase = "starting";
   return {{"phase", callPhase}, {"kind", callKind}};
 }
 if (op == "call_started") {
   if (callPhase != "starting")
     throw std::runtime_error("当前状态不能创建通话");
   auto c = in.at("call");
   if (!c.is_object() || !c.contains("id") || !c["id"].is_string() ||
       !c.contains("kind") || c["kind"] != callKind || !c.contains("owner") ||
       !c["owner"].is_string())
     throw std::runtime_error("通话创建响应无效");
   auto id = c["id"].get<std::string>();
   if (id.empty() || id.size() > 80 ||
       !std::all_of(id.begin(), id.end(), [](unsigned char ch) {
         return std::isalnum(ch) || ch == '-';
       }))
     throw std::runtime_error("通话标识无效");
   callId = id;
   callPhase = "ringing";
   return {{"phase", callPhase}, {"id", callId}, {"kind", callKind}};
 }
 if (op == "call_joined") {
   if (callPhase != "ringing" && callPhase != "joining")
     throw std::runtime_error("当前状态不能加入通话");
   auto c = in.at("call");
   auto servers = in.at("iceServers");
   if (!c.is_object() || c.value("id", std::string()) != callId ||
       c.value("kind", std::string()) != callKind ||
       !c.value("joined", false) || !c.contains("cap") ||
       !c["cap"].is_string() || c["cap"].get<std::string>().empty() ||
       c["cap"].get<std::string>().size() > 512)
     throw std::runtime_error("通话加入响应无效");
   if (!servers.is_array() || servers.empty() || servers.size() > 16)
     throw std::runtime_error("ICE 服务列表无效");
   for (const auto &server : servers) {
     if (!server.is_object() || !server.contains("urls"))
       throw std::runtime_error("ICE 服务格式无效");
     J urls = server["urls"];
     if (urls.is_string())
       urls = J::array({urls});
     if (!urls.is_array() || urls.empty() || urls.size() > 8)
       throw std::runtime_error("ICE 地址无效");
     for (const auto &u : urls) {
       if (!u.is_string())
         throw std::runtime_error("ICE 地址无效");
       auto text = u.get<std::string>();
       if (text.size() > 512 ||
           !(text.rfind("stun:", 0) == 0 || text.rfind("stuns:", 0) == 0 ||
             text.rfind("turn:", 0) == 0 || text.rfind("turns:", 0) == 0))
         throw std::runtime_error("ICE 地址协议无效");
     }
   }
   callPhase = "joined";
   return {{"phase", callPhase},
           {"id", callId},
           {"kind", callKind},
           {"iceServers", servers}};
 }
 if (op == "call_published") {
   if (callPhase != "joined" && callPhase != "publishing")
     throw std::runtime_error("当前状态不能发布媒体");
   auto response = in.at("response");
   if (!response.is_object() || !response.contains("sessionDescription") ||
       !response["sessionDescription"].is_object())
     throw std::runtime_error("SFU 发布应答无效");
   const auto &s = response["sessionDescription"];
   if (s.value("type", std::string()) != "answer" || !s.contains("sdp") ||
       !s["sdp"].is_string())
     throw std::runtime_error("SFU 发布 SDP 类型无效");
   auto text = s["sdp"].get<std::string>();
   if (text.size() < 4 || text.size() >= 60000 || text.rfind("v=0", 0) != 0)
     throw std::runtime_error("SFU 发布 SDP 无效");
   if (!response.contains("tracks") || !response["tracks"].is_array() ||
       response["tracks"].empty() || response["tracks"].size() > 2)
     throw std::runtime_error("SFU 发布轨道应答无效");
   callPhase = "published";
   return {{"phase", callPhase},
           {"sessionDescription", s},
           {"tracks", response["tracks"]}};
 }
 if (op == "call_ready") {
   if (callPhase != "published" && callPhase != "ready")
     throw std::runtime_error("当前状态不能确认媒体就绪");
   callPhase = "ready";
   return {{"phase", callPhase}, {"id", callId}};
 }
 if (op == "call_subscriber_reset") {
   if (callPhase != "ready" && callPhase != "subscribed" && callPhase != "answered" && callPhase != "subscribing")
     throw std::runtime_error("当前状态不能重建媒体接收连接");
   callPhase = "ready";
   return {{"phase", callPhase}, {"id", callId}};
 }
 if (op == "call_subscribed") {
   if (callPhase != "ready" && callPhase != "answered" &&
       callPhase != "subscribing")
     throw std::runtime_error("当前状态不能订阅媒体");
   auto response = in.at("response");
   if (!response.is_object() ||
       response.value("requiresImmediateRenegotiation", false) != true ||
       !response.contains("sessionDescription") ||
       !response["sessionDescription"].is_object())
     throw std::runtime_error("SFU 订阅应答无效");
   const auto &s = response["sessionDescription"];
   if (s.value("type", std::string()) != "offer" || !s.contains("sdp") ||
       !s["sdp"].is_string())
     throw std::runtime_error("SFU 订阅 SDP 类型无效");
   auto text = s["sdp"].get<std::string>();
   if (text.size() < 4 || text.size() >= 60000 || text.rfind("v=0", 0) != 0)
     throw std::runtime_error("SFU 订阅 SDP 无效");
   if (!response.contains("tracks") || !response["tracks"].is_array() ||
       response["tracks"].empty() || response["tracks"].size() > 16)
     throw std::runtime_error("SFU 订阅轨道应答无效");
   for (const auto &t : response["tracks"]) {
     if (!t.is_object() || !t.contains("mid") || !t["mid"].is_string() ||
         !t.contains("trackName") || !t["trackName"].is_string())
       throw std::runtime_error("SFU 订阅轨道格式无效");
     if (t.contains("errorCode") && !t["errorCode"].is_null() &&
         t["errorCode"] != 0 && t["errorCode"] != "")
       throw std::runtime_error("SFU 媒体轨道订阅失败");
   }
   callPhase = "subscribed";
   return {{"phase", callPhase},
           {"sessionDescription", s},
           {"tracks", response["tracks"]}};
 }
 if (op == "call_answered") {
   if (callPhase != "subscribed" && callPhase != "answered")
     throw std::runtime_error("当前状态没有待确认的远端媒体");
   callPhase = "answered";
   return {{"phase", callPhase}, {"id", callId}};
 }
 if (op == "call_failed") {
   if (callPhase == "idle")
     return {{"phase", callPhase}};
   callPhase = "failed";
   return {{"phase", callPhase}, {"id", callId}};
 }
 if (op == "call_cleanup") {
   callPhase = "idle";
   callId.clear();
   callKind.clear();
   return {{"phase", callPhase}};
 }
 if (op == "call_state")
   return {{"phase", callPhase}, {"id", callId}, {"kind", callKind}};
 if (op == "reset") {
   auto r = validText(in.at("room"), 80);
   if (!std::all_of(
           r.begin(), r.end(),
           [](unsigned char c) { return std::isalnum(c) || c == '-'; }) ||
       std::any_of(r.begin(), r.end(), [](unsigned char c) { return c > 127; }))
     throw std::runtime_error("房号格式不正确");
   room = r;
   messages.clear();
   deleted.clear();
   members = J::array();
   callPhase = "idle";
   callId.clear();
   callKind.clear();
   return {{"room", room}};
 }
 if(op=="session")return {{"nickname",validText(in.at("nickname"),32)}};
 if(op=="room")return {{"name",validText(in.at("name"),80)},{"password",validText(in.at("password"),128)}};
 if(op=="join")return {{"password",validText(in.at("password"),128)}};
 if(op=="send")return {{"text",validText(in.at("text"),4000)},{"clientId",validText(in.at("clientId"),80)}};
 if(op=="send_response"){
  auto status=in.at("status");if(!status.is_number_integer())throw std::runtime_error("消息响应状态异常");int code=status.get<int>();
  auto raw=in.at("body");if(!raw.is_string())throw std::runtime_error("消息响应格式异常");auto body=raw.get<std::string>();if(body.size()>65536)throw std::runtime_error("消息响应过大");
  J response;try{response=J::parse(body);}catch(...){throw std::runtime_error("消息响应格式异常");}
  if(code!=200){std::string message="发送失败（"+std::to_string(code)+"）";if(response.is_object()&&response.contains("error")&&response["error"].is_string()){auto detail=response["error"].get<std::string>();if(detail.size()>256)detail.resize(256);if(!detail.empty())message=detail;}throw std::runtime_error(message);}
  if(!response.is_object()||!response.contains("message")||!response["message"].is_object())throw std::runtime_error("消息响应格式异常");
  const auto& m=response["message"];
  auto expectedClient=validText(in.at("clientId"),80);auto expectedText=validText(in.at("text"),4000);
  if(!in.contains("room")||!in["room"].is_string()||in["room"].get<std::string>()!=room)throw std::runtime_error("房间已切换");
  if(!m.contains("id")||!m["id"].is_string()||m["id"].get<std::string>().empty()||m["id"].get<std::string>().size()>80)throw std::runtime_error("消息标识异常");
  if(!m.contains("nickname")||!m["nickname"].is_string()||validText(m["nickname"],32)!=m["nickname"].get<std::string>())throw std::runtime_error("昵称格式异常");
  if(!m.contains("client_id")||!m["client_id"].is_string()||m["client_id"].get<std::string>()!=expectedClient)throw std::runtime_error("消息标识不匹配");
  if(!m.contains("kind")||!m["kind"].is_string()||m["kind"]!="text")throw std::runtime_error("消息类型异常");
  if(!m.contains("text")||!m["text"].is_string()||m["text"].get<std::string>()!=expectedText)throw std::runtime_error("消息内容不匹配");
  if(!m.contains("created")||!m["created"].is_number_integer()||m["created"].get<int64_t>()<=0)throw std::runtime_error("消息时间异常");
  if(!m.contains("mine")||!m["mine"].is_boolean()||!m["mine"].get<bool>())throw std::runtime_error("发送结果归属异常");
  if(m.contains("deleted")&&(!m["deleted"].is_number_integer()||m["deleted"].get<int>()!=0))throw std::runtime_error("消息状态异常");
  merge(m);return {{"message",m},{"count",messages.size()}};
 }
 if(op=="upload"){auto size=in.at("size").get<int64_t>();auto mime=validText(in.at("mime"),100);auto kind=std::string("file");if(mime=="image/png"||mime=="image/jpeg"||mime=="image/webp"||mime=="image/gif")kind="image";else if(mime=="video/mp4"||mime=="video/webm"||mime=="video/quicktime")kind="video";else if(in.value("voice",false)){if(mime!="audio/mp4")throw std::runtime_error("录音类型异常");kind="audio";}int64_t cap=(kind=="video"?100:kind=="file"?50:10)*1024LL*1024LL;if(size<=0||size>cap)throw std::runtime_error("文件为空或超过大小限制");return {{"size",size},{"mime",mime},{"kind",kind},{"filename",validText(in.at("filename"),180)},{"clientId",validText(in.at("clientId"),80)},{"duration",in.value("duration",0.0)}};}
 if(op=="history"){for(auto& m:in.at("messages"))merge(m);return {{"count",messages.size()}};}
 if(op=="event"){const auto& e=in.at("event");auto type=e.value("type","");if(type=="message")merge(e.at("message"));else if(type=="deleted"){auto id=e.at("id").get<std::string>();deleted.insert(id);if(messages.count(id)){messages[id]["deleted"]=1;messages[id]["text"]=nullptr;}}else if(type=="presence")members=e.at("members");return {{"type",type}};}
 if(op=="snapshot"){J rows=J::array();std::vector<J> sorted;for(auto& [key,v]:messages)sorted.push_back(v);std::sort(sorted.begin(),sorted.end(),[](const J&a,const J&b){auto x=a.at("created").get<int64_t>(),y=b.at("created").get<int64_t>();return x==y?a.at("id").get<std::string>()<b.at("id").get<std::string>():x<y;});for(auto& m:sorted)rows.push_back(m);return {{"messages",rows},{"members",members},{"room",room}};}
 if(op=="retry"){int attempt=std::clamp(in.value("attempt",0),0,5);return {{"delay",std::min(30000,1000*(1<<attempt))}};}
 throw std::runtime_error("未知核心操作");
}
}

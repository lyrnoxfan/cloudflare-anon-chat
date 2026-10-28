#include "engine.hpp"
#include <jni.h>
#include "unicode.hpp"
#include <mutex>
#include <memory>
#include <unordered_map>
namespace {
std::mutex lock;
std::unordered_map<jlong,std::unique_ptr<ql::Engine>> engines;
jlong nextId=1;
std::string fromJava(JNIEnv* e,jstring s){if(!s)throw std::runtime_error("空参数");if(e->GetStringLength(s)>8*1024*1024)throw std::runtime_error("数据过大");const jchar* p=e->GetStringChars(s,nullptr);if(!p)throw std::runtime_error("读取参数失败");std::u16string t(reinterpret_cast<const char16_t*>(p),e->GetStringLength(s));e->ReleaseStringChars(s,p);return ql::utf8(t);}
jstring toJava(JNIEnv* e,const std::string&s){auto t=ql::utf16(s);return e->NewString(reinterpret_cast<const jchar*>(t.data()),static_cast<jsize>(t.size()));}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_lyrnox_qingliao_NativeCore_create(JNIEnv*,jclass){std::lock_guard<std::mutex> guard(lock);auto id=nextId++;engines[id]=std::make_unique<ql::Engine>();return id;}
extern "C" JNIEXPORT void JNICALL Java_com_lyrnox_qingliao_NativeCore_destroy(JNIEnv*,jclass,jlong id){std::lock_guard<std::mutex> guard(lock);engines.erase(id);}
extern "C" JNIEXPORT jstring JNICALL Java_com_lyrnox_qingliao_NativeCore_execute(JNIEnv* e,jclass,jlong id,jstring input){try{auto data=fromJava(e,input);if(data.size()>16*1024*1024)throw std::runtime_error("数据过大");std::lock_guard<std::mutex> guard(lock);auto it=engines.find(id);if(it==engines.end())throw std::runtime_error("核心已关闭");return toJava(e,ql::J({{"ok",true},{"value",it->second->command(ql::J::parse(data))}}).dump());}catch(const std::exception& ex){return toJava(e,ql::J({{"ok",false},{"error",ex.what()}}).dump());}}

#pragma once
#include "vendor/json.hpp"
#include <map>
#include <set>
#include <string>
namespace ql {
using J=nlohmann::json;
class Engine {
 std::map<std::string,J> messages;
 std::set<std::string> deleted;
 J members=J::array();
 std::string room="lobby";
 std::string sessionPhase="unknown";
 std::string sessionId;
 std::string sessionNickname;
 int64_t sessionExpires=0;
 public:
 J command(const J& in);
 static size_t units(const std::string& s);
 static std::string validText(const J& v,size_t max,size_t min=1);
 void merge(const J& m);
};
}

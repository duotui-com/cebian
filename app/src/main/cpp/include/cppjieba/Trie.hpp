#ifndef CPPJIEBA_TRIE_HPP
#define CPPJIEBA_TRIE_HPP

#include <algorithm>
#include <cstdint>
#include <map>
#include <unordered_map>
#include <vector>
#include "Utils.hpp"
#include "Unicode.hpp"

namespace cppjieba {

using namespace std;

const size_t MAX_WORD_LENGTH = 512;

struct DictUnit {
  Unicode word;
  double weight;
  string tag;
}; // struct DictUnit

struct Dag {
  RuneStr runestr;
  // [offset, value]
  LocalVector<pair<size_t, const DictUnit*> > nexts;
  const DictUnit * pInfo;
  double weight;
  size_t nextPos; // TODO
  Dag():runestr(), pInfo(NULL), weight(0.0), nextPos(0) {
  }
}; // struct Dag

typedef Rune TrieKey;

/**
 * 紧凑双数组 trie（double-array trie）。
 *
 * 原实现是"每个节点挂一个 std::unordered_map<TrieKey, TrieNode*>"，节点对象 +
 * map 对象 + 桶数组 + 表项，35 万词 / 50 万节点的中文词典在真机上实测常驻约
 * 100 MB。这里改成双数组（base/check 各 4 字节/槽，游标单调前进的 first-fit
 * 打包），词尾值压成稀疏表，字符表按出现频率排序（高频字用小编号，数组才紧凑）。
 *
 * 对外接口与语义保持与原实现一致：Find 的两个重载、InsertNode/DeleteNode
 * （用于运行期用户词，走旁路表）都可用。
 */
class Trie {
 public:
  Trie(const vector<Unicode>& keys, const vector<const DictUnit*>& valuePointers) {
    BuildDoubleArray(keys, valuePointers);
  }
  ~Trie() {
  }

  const DictUnit* Find(RuneStrArray::const_iterator begin, RuneStrArray::const_iterator end) const {
    if (begin == end) {
      return NULL;
    }
    int32_t slot = 0;
    for (RuneStrArray::const_iterator it = begin; it != end; it++) {
      slot = Step(slot, it->rune);
      if (slot <= 0) {
        return NULL;
      }
    }
    return ValueAt(slot);
  }

  void Find(RuneStrArray::const_iterator begin,
        RuneStrArray::const_iterator end,
        vector<struct Dag>&res,
        size_t max_word_len = MAX_WORD_LENGTH) const {
    const size_t n = size_t(end - begin);
    res.resize(n);

    for (size_t i = 0; i < n; i++) {
      res[i].runestr = *(begin + i);

      int32_t slot = Step(0, res[i].runestr.rune);
      if (slot > 0) {
        res[i].nexts.push_back(pair<size_t, const DictUnit*>(i, ValueAt(slot)));
      } else {
        res[i].nexts.push_back(pair<size_t, const DictUnit*>(i, static_cast<const DictUnit*>(NULL)));
      }

      for (size_t j = i + 1; j < n && (j - i + 1) <= max_word_len; j++) {
        if (slot <= 0) {
          break;
        }
        slot = Step(slot, (begin + j)->rune);
        if (slot <= 0) {
          break;
        }
        const DictUnit* value = ValueAt(slot);
        if (NULL != value) {
          res[i].nexts.push_back(pair<size_t, const DictUnit*>(j, value));
        }
      }

      if (!extra_words_.empty()) {
        AppendExtraMatches(begin, end, i, max_word_len, res[i]);
      }
    }
  }

  void InsertNode(const Unicode& key, const DictUnit* ptValue) {
    if (key.begin() == key.end()) {
      return;
    }
    extra_words_[key] = ptValue;
  }

  void DeleteNode(const Unicode& key, const DictUnit* ptValue) {
    extra_words_.erase(key);
  }

 private:
  struct FreqDesc {
    bool operator()(const pair<Rune, int32_t>& lhs, const pair<Rune, int32_t>& rhs) const {
      if (lhs.second != rhs.second) {
        return lhs.second > rhs.second;
      }
      return lhs.first < rhs.first;
    }
  };
  struct PairRuneAsc {
    bool operator()(const pair<Rune, int32_t>& lhs, const pair<Rune, int32_t>& rhs) const {
      return lhs.first < rhs.first;
    }
  };
  struct PairRuneLessThanRune {
    bool operator()(const pair<Rune, int32_t>& lhs, Rune rhs) const {
      return lhs.first < rhs;
    }
  };
  struct EndLess {
    bool operator()(const pair<int32_t, const DictUnit*>& lhs,
                    const pair<int32_t, const DictUnit*>& rhs) const {
      return lhs.first < rhs.first;
    }
  };
  struct UnicodeLess {
    bool operator()(const Unicode& lhs, const Unicode& rhs) const {
      const size_t n = lhs.size() < rhs.size() ? lhs.size() : rhs.size();
      for (size_t i = 0; i < n; i++) {
        if (lhs[i] != rhs[i]) {
          return lhs[i] < rhs[i];
        }
      }
      return lhs.size() < rhs.size();
    }
  };
  struct TempNode {
    vector<pair<int32_t, int32_t> > children;  // (label, child index)
    const DictUnit* value;
    size_t slot;
    TempNode() : value(NULL), slot(0) {
    }
  };

  int32_t Step(int32_t slot, Rune rune) const {
    if (slot < 0 || size_t(slot) >= base_.size()) {
      return -1;
    }
    const int32_t label = LabelOf(rune);
    if (label == 0) {
      return -1;
    }
    const int32_t next = base_[slot] + label;
    if (next <= 0 || size_t(next) >= check_.size() || check_[next] != slot) {
      return -1;
    }
    return next;
  }

  int32_t LabelOf(Rune rune) const {
    vector<pair<Rune, int32_t> >::const_iterator it =
        std::lower_bound(alphabet_.begin(), alphabet_.end(), rune, PairRuneLessThanRune());
    if (it == alphabet_.end() || it->first != rune) {
      return 0;
    }
    return it->second;
  }

  const DictUnit* ValueAt(int32_t slot) const {
    vector<int32_t>::const_iterator it =
        std::lower_bound(words_.begin(), words_.end(), slot);
    if (it == words_.end() || *it != slot) {
      return NULL;
    }
    return word_values_[size_t(it - words_.begin())];
  }

  void AppendExtraMatches(RuneStrArray::const_iterator begin,
                          RuneStrArray::const_iterator end,
                          size_t start,
                          size_t max_word_len,
                          Dag& dag) const {
    Unicode word;
    const size_t n = size_t(end - begin);
    for (size_t j = start; j < n && (j - start + 1) <= max_word_len; j++) {
      word.push_back((begin + j)->rune);
      map<Unicode, const DictUnit*, UnicodeLess>::const_iterator it = extra_words_.find(word);
      if (it != extra_words_.end()) {
        dag.nexts.push_back(pair<size_t, const DictUnit*>(j, it->second));
      }
    }
  }

  void BuildDoubleArray(const vector<Unicode>& keys,
                        const vector<const DictUnit*>& valuePointers) {
    base_.assign(2, 0);
    check_.assign(2, 0);
    if (keys.empty()) {
      return;
    }

    // 1) 字符频率 -> 紧凑 label（高频字取小编号）。
    unordered_map<Rune, int32_t> freq;
    freq.reserve(1 << 14);
    for (size_t i = 0; i < keys.size(); i++) {
      const Unicode& key = keys[i];
      for (size_t j = 0; j < key.size(); j++) {
        freq[key[j]]++;
      }
    }
    vector<pair<Rune, int32_t> > chars;
    chars.reserve(freq.size());
    for (unordered_map<Rune, int32_t>::const_iterator it = freq.begin(); it != freq.end(); ++it) {
      chars.push_back(make_pair(it->first, it->second));
    }
    std::sort(chars.begin(), chars.end(), FreqDesc());
    unordered_map<Rune, int32_t> label_of;
    label_of.reserve(chars.size() * 2);
    alphabet_.reserve(chars.size());
    for (size_t i = 0; i < chars.size(); i++) {
      const int32_t label = int32_t(i) + 1;
      alphabet_.push_back(make_pair(chars[i].first, label));
      label_of[chars[i].first] = label;
    }
    std::sort(alphabet_.begin(), alphabet_.end(), PairRuneAsc());

    // 2) 临时树（只在构建期存在）。
    vector<TempNode> nodes;
    nodes.reserve(keys.size() * 2);
    nodes.push_back(TempNode());
    for (size_t i = 0; i < keys.size(); i++) {
      const Unicode& key = keys[i];
      if (key.empty()) {
        continue;
      }
      int32_t node = 0;
      for (size_t j = 0; j < key.size(); j++) {
        const int32_t label = label_of[key[j]];
        int32_t child = -1;
        vector<pair<int32_t, int32_t> >& children = nodes[node].children;
        for (size_t k = 0; k < children.size(); k++) {
          if (children[k].first == label) {
            child = children[k].second;
            break;
          }
        }
        if (child < 0) {
          nodes.push_back(TempNode());
          child = int32_t(nodes.size()) - 1;
          children.push_back(make_pair(label, child));
        }
        node = child;
      }
      nodes[node].value = valuePointers[i];
    }

    // 3) BFS 打包装进 base/check。
    vector<bool> occupied(2, false);
    occupied[0] = true;
    vector<int32_t> queue;
    queue.reserve(nodes.size());
    queue.push_back(0);
    size_t head = 0;
    int32_t cursor = 1;
    while (head < queue.size()) {
      const int32_t node = queue[head++];
      vector<pair<int32_t, int32_t> >& children = nodes[node].children;
      if (children.empty()) {
        continue;
      }
      std::sort(children.begin(), children.end());
      const int32_t max_label = children.back().first;
      int32_t b = cursor;
      for (;;) {
        const size_t need = size_t(b + max_label) + 1;
        if (base_.size() < need) {
          base_.resize(need, 0);
          check_.resize(need, 0);
          occupied.resize(need, false);
        }
        bool ok = true;
        for (size_t k = 0; k < children.size(); k++) {
          if (occupied[b + children[k].first]) {
            ok = false;
            break;
          }
        }
        if (ok) {
          break;
        }
        b++;
      }
      base_[nodes[node].slot] = b;
      for (size_t k = 0; k < children.size(); k++) {
        const int32_t slot = b + children[k].first;
        occupied[slot] = true;
        check_[slot] = int32_t(nodes[node].slot);
        nodes[children[k].second].slot = size_t(slot);
        queue.push_back(children[k].second);
      }
      cursor = b;
    }

    // 4) 词尾信息压成稀疏表（只存有值的槽）。
    vector<pair<int32_t, const DictUnit*> > ends;
    ends.reserve(keys.size());
    for (size_t i = 1; i < nodes.size(); i++) {
      if (nodes[i].value != NULL) {
        ends.push_back(make_pair(int32_t(nodes[i].slot), nodes[i].value));
      }
    }
    std::sort(ends.begin(), ends.end(), EndLess());
    words_.reserve(ends.size());
    word_values_.reserve(ends.size());
    for (size_t i = 0; i < ends.size(); i++) {
      words_.push_back(ends[i].first);
      word_values_.push_back(ends[i].second);
    }
  }

  vector<int32_t> base_;
  vector<int32_t> check_;
  vector<int32_t> words_;
  vector<const DictUnit*> word_values_;
  vector<pair<Rune, int32_t> > alphabet_;
  map<Unicode, const DictUnit*, UnicodeLess> extra_words_;
}; // class Trie

} // namespace cppjieba

#endif

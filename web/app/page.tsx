'use client';

import { useState } from 'react';
import 'bootstrap/dist/css/bootstrap.min.css'; // layout.tsx に移動済みだが念のため

export default function Home() {
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSearch = async (event: React.FormEvent) => {
    event.preventDefault();
    setIsLoading(true);
    setError(null);
    setSearchResults([]); // 検索開始時に結果をクリア

    try {
      console.log('Sending search request to API for:', searchQuery);
      const response = await fetch('/api/search', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ query: searchQuery }),
      });

      if (!response.ok) {
        let errorMessage = `API request failed with status ${response.status}`;
        try {
          const errorData = await response.json();
          errorMessage = errorData.error || errorMessage;
        } catch (e) {
          // JSONパースに失敗した場合でも基本的なエラーメッセージは表示
          console.error("Failed to parse error response JSON:", e);
        }
        throw new Error(errorMessage);
      }

      const data = await response.json();
      if (data.results && Array.isArray(data.results)) {
        setSearchResults(data.results);
        if (data.results.length === 0) {
          // 検索結果0件はエラーではないが、ユーザーにフィードバック
          // setError('検索結果が見つかりませんでした。'); // 必要であればコメント解除
        }
      } else {
        console.error('Invalid response format from API:', data);
        throw new Error('APIから無効な形式のレスポンスが返されました。');
      }

    } catch (err) {
      console.error('Search failed:', err);
      // err が Error インスタンスかチェック
      if (err instanceof Error) {
        setError(`検索中にエラーが発生しました: ${err.message}`);
      } else {
        setError('検索中に不明なエラーが発生しました。');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="container mt-5">
      <h1 className="mb-4">画像検索</h1>

      <form onSubmit={handleSearch} className="mb-4">
        <div className="input-group">
          <input
            type="text"
            className="form-control"
            placeholder="検索キーワードを入力..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            disabled={isLoading}
          />
          <button
            className="btn btn-primary"
            type="submit"
            disabled={isLoading || !searchQuery.trim()}
          >
            {isLoading ? '検索中...' : '検索'}
          </button>
        </div>
      </form>

      {error && <div className="alert alert-danger">{error}</div>}

      {isLoading && (
        <div className="d-flex justify-content-center">
          <div className="spinner-border" role="status">
            <span className="visually-hidden">Loading...</span>
          </div>
        </div>
      )}

      {!isLoading && searchResults.length > 0 && (
        <div>
          <h2>検索結果:</h2>
          <ul className="list-group">
            {searchResults.map((filePath, index) => (
              <li key={index} className="list-group-item">
                {filePath}
              </li>
            ))}
          </ul>
        </div>
      )}

      {!isLoading && !error && searchResults.length === 0 && searchQuery && !isLoading && (
        <p>検索結果が見つかりませんでした。</p> // 検索実行後、結果0件の場合のメッセージ
      )}
      {!isLoading && !error && searchResults.length === 0 && !searchQuery && (
        <p>検索キーワードを入力してください。</p> // 初期状態のメッセージ
      )}
      {/* 初期状態や検索結果0件の場合の表示は必要に応じて調整 */}

    </div>
  );
}

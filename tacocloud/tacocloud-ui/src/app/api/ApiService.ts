import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs/Observable';

/**
 * Thin wrapper over the TacoCloud API.
 *
 * <p>Every method takes a path relative to the API root and returns the
 * observable untouched, so callers decide when to subscribe. Nothing here
 * subscribes on the caller's behalf.
 */
@Injectable()
export class ApiService {

  private static readonly base = 'http://localhost:8080';

  constructor(private http: HttpClient) {
  }

  private static toParams(values?: { [param: string]: string | number }): HttpParams {
    let params = new HttpParams();
    if (!values) {
      return params;
    }
    Object.keys(values).forEach(key => {
      const value = values[key];
      // Unset filters are left out instead of sent empty: the server treats
      // "absent" as "no filter", so sending "" would be a question it has to
      // guess the meaning of.
      if (value !== undefined && value !== null && `${value}` !== '') {
        params = params.set(key, `${value}`);
      }
    });
    return params;
  }

  private static jsonHeaders(): HttpHeaders {
    return new HttpHeaders({ 'Content-Type': 'application/json' });
  }

  get<T>(path: string, params?: { [param: string]: string | number }): Observable<T> {
    return this.http.get<T>(ApiService.base + path, {
      params: ApiService.toParams(params)
    });
  }

  post<T>(path: string, body: any): Observable<T> {
    return this.http.post<T>(ApiService.base + path, body, {
      headers: ApiService.jsonHeaders()
    });
  }

  /** The catalog search of TC-19, with the filters the user actually set. */
  searchTacos(filters: {
    name?: string;
    ingredientId?: string;
    diet?: string;
    excludeAllergen?: string;
    spice?: string;
    sort?: string;
    page?: number;
    size?: number;
  }): Observable<any> {
    return this.get<any>('/api/tacos', filters);
  }

  /** Today's taco (TC-20). */
  getTacoOfTheDay(): Observable<any> {
    return this.get<any>('/api/tacos/today');
  }

  /** The customer's own favorites (TC-21). */
  getFavorites(): Observable<any> {
    return this.get<any>('/api/users/me/favorites');
  }

  saveFavorite(tacoId: string): Observable<any> {
    return this.put<any>(`/api/users/me/favorites/${tacoId}`, null);
  }

  removeFavorite(tacoId: string): Observable<any> {
    return this.delete<any>(`/api/users/me/favorites/${tacoId}`);
  }

  /** The customer rates a taco as themselves (TC-22). */
  rateTaco(tacoId: string, score: number): Observable<any> {
    return this.put<any>(`/api/tacos/${tacoId}/rating`, { score: score });
  }

  /** The ranking, best rated first (TC-22). */
  getTopRatedTacos(limit?: number): Observable<any> {
    return this.get<any>('/api/tacos/top',
      limit === undefined ? undefined : { limit: limit });
  }

  /** The customer's own order history (TC-23). */
  getMyOrders(page?: number, size?: number): Observable<any> {
    return this.get<any>('/api/users/me/orders', {
      page: page === undefined ? 0 : page,
      size: size === undefined ? 10 : size
    });
  }

  getMyOrder(orderId: string): Observable<any> {
    return this.get<any>(`/api/users/me/orders/${orderId}`);
  }

  /**
   * Orders again (TC-24). When the current price differs, the server answers
   * with a quote and no order; the UI shows it and calls this method again with
   * confirmPriceChange set.
   */
  reorder(orderId: string, paymentMethodId: string, confirmPriceChange: boolean): Observable<any> {
    return this.post<any>(`/api/orders/${orderId}/reorder`, {
      paymentMethodId: paymentMethodId,
      confirmPriceChange: confirmPriceChange
    });
  }

  private put<T>(path: string, body: any): Observable<T> {
    return this.http.put<T>(ApiService.base + path, body, {
      headers: ApiService.jsonHeaders()
    });
  }

  private delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(ApiService.base + path);
  }

}

import { Injectable } from '@angular/core';
import { Observable } from 'rxjs/Observable';
import { ApiService } from '../api/ApiService';

/**
 * The home page's "recent tacos" strip.
 *
 * <p>It used to ask for `/tacos?recent`, which is not a route this API serves:
 * the catalog lives under `/api`. The request 404'd and the strip stayed empty.
 */
@Injectable()
export class RecentTacosService {

  constructor(private apiService: ApiService) {
  }

  getRecentTacos(): Observable<any> {
    return this.apiService.get<any>('/api/tacos?recent');
  }

}

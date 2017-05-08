/*
 * Copyright (c) 2015-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 */
'use strict';

export interface ICommunicationClient {
  connect(): ng.IPromise<any>;
  disconnect(): void;
  send();
}

interface IRequest {
  jsonrpc: string;
  id: string;
  method: string;
  params: any;
}

interface IResponse {
  jsonrpc: string;
  id: string;
  result?: any;
  error?: IError;
}

interface INotification {
  jsonrpc: string;
  method: string;
  params: any;
}

interface IError {
  number: number;
  message: string;
  data?: any;
}

/**
 * This client is handling the JSON RPC requests, responses and notifications.
 *
 * @author Ann Shumilova
 */
export class JsonRpcClient {
  private client: ICommunicationClient;

  constructor (client: ICommunicationClient) {
    this.client = client;
  }

  request(): ng.IPromise<any> {
  }

  notify(): void {
  }
}


